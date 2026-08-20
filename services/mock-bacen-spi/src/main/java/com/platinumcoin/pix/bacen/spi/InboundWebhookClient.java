package com.platinumcoin.pix.bacen.spi;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The rail's side of the inbound delivery (step 37): {@code POST} a payment to the participant's webhook
 * ({@code settlement-service /v1/inbound/pix}), presenting the shared {@code X-Webhook-Token}, and
 * <b>retry the way BACEN would</b>.
 *
 * <h2>Retrying is the feature, not an implementation detail</h2>
 * This stub exists to make the participant's <i>idempotency</i> provable. A rail that delivered once and
 * gave up would never exercise the dedupe on the other side; because this one re-presents a payment whose
 * outcome it does not know, a redelivery is something the platform actually survives in a demo rather than
 * only in a unit test. It is the same reason {@code POST /spi/settlements} can be told to time out.
 *
 * <h2>What is retried, and what is not</h2>
 * <ul>
 *   <li><b>{@code 5xx} or no answer at all</b> (timeout, connection refused) — the outcome is unknown, so
 *       the payment is re-presented after a short delay. This is precisely the case that can deliver the
 *       same {@code endToEndId} twice: the first attempt may have credited and then failed to answer.</li>
 *   <li><b>{@code 4xx}</b> — a decision, not a failure: a wrong token or a key nobody here answers for.
 *       Retrying would be refused identically, so the rail stops immediately and bounces the payment back
 *       to the payer's PSP. Retrying a {@code 401} forever is how a real integration wedges itself.</li>
 * </ul>
 *
 * <p>The token is read from configuration and <b>never logged</b> (ADR-0012) — only whether one was
 * configured at all, which is the fact an operator debugging a {@code 401} actually needs.
 */
@Component
public class InboundWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(InboundWebhookClient.class);

    /** The shared-secret header the participant validates. Not {@code Authorization}: not a bearer token. */
    private static final String WEBHOOK_TOKEN_HEADER = "X-Webhook-Token";

    /** Wire shape of the delivery — mirrors settlement-service's {@code InboundPixRequest}. */
    record Delivery(String endToEndId, String pixKey, long amountCents, String payerName,
            String payerIspb) {
    }

    /** What the participant answered. {@code outcome} is its own word for what it did with the payment. */
    public record DeliveryReceipt(String txId, String outcome, int attempts) {
    }

    private record Ack(String endToEndId, String txId, String outcome) {
    }

    private final RestClient restClient;
    private final String webhookToken;
    private final int maxAttempts;
    private final long retryDelayMs;

    public InboundWebhookClient(
            RestClient.Builder builder,
            @Value("${bacen.inbound.participant-base-url}") String participantBaseUrl,
            @Value("${bacen.inbound.webhook-token:}") String webhookToken,
            @Value("${bacen.inbound.max-attempts:3}") int maxAttempts,
            @Value("${bacen.inbound.retry-delay-ms:500}") long retryDelayMs,
            @Value("${bacen.inbound.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${bacen.inbound.read-timeout-ms:5000}") long readTimeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(participantBaseUrl).requestFactory(factory).build();
        this.webhookToken = webhookToken;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        log.info("Inbound delivery client ready, it will present payments to the participant's webhook | "
                        + "participantBaseUrl={} maxAttempts={} retryDelayMs={} tokenConfigured={}",
                participantBaseUrl, this.maxAttempts, this.retryDelayMs,
                webhookToken != null && !webhookToken.isBlank());
    }

    /**
     * Present one payment until the participant answers, or until the attempt budget runs out.
     *
     * @throws InboundDeliveryFailedException the participant refused permanently, or never answered
     */
    public DeliveryReceipt deliver(String endToEndId, String pixKey, long amountCents, String payerName,
            String payerIspb) {
        Delivery delivery = new Delivery(endToEndId, pixKey, amountCents, payerName, payerIspb);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info("Delivering an inbound Pix to the participant's webhook | endToEndId={} pixKey={} "
                            + "amountCents={} payerName={} payerIspb={} attempt={} of {}",
                    endToEndId, pixKey, amountCents, payerName, payerIspb, attempt, maxAttempts);
            try {
                Ack ack = restClient.post()
                        .uri("/v1/inbound/pix")
                        .header(WEBHOOK_TOKEN_HEADER, webhookToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(delivery)
                        .retrieve()
                        .body(Ack.class);

                String outcome = ack == null ? "UNKNOWN" : ack.outcome();
                log.info("The participant accepted the inbound Pix | endToEndId={} txId={} outcome={} "
                                + "attempts={}",
                        endToEndId, ack == null ? null : ack.txId(), outcome, attempt);
                return new DeliveryReceipt(ack == null ? null : ack.txId(), outcome, attempt);

            } catch (RestClientResponseException e) {
                HttpStatusCode status = e.getStatusCode();
                if (status.is4xxClientError()) {
                    // A decision, not a failure. Stop: a real rail bounces this back to the payer's PSP.
                    log.warn("The participant REFUSED the inbound Pix permanently, the rail stops "
                                    + "re-presenting it and would bounce it back to the payer's PSP | "
                                    + "endToEndId={} pixKey={} status={} attempt={} body={}",
                            endToEndId, pixKey, status.value(), attempt, e.getResponseBodyAsString());
                    throw new InboundDeliveryFailedException(
                            "the participant refused the delivery with status " + status.value(),
                            true, attempt, status.value());
                }
                logTransient(endToEndId, attempt, status.value(), e.getMessage());
                if (attempt == maxAttempts) {
                    throw exhausted(endToEndId, attempt, status.value());
                }
            } catch (ResourceAccessException e) {
                // No answer at all: the participant may have credited and failed to tell us, which is
                // exactly the case its endToEndId dedupe exists for.
                logTransient(endToEndId, attempt, null, e.getMessage());
                if (attempt == maxAttempts) {
                    throw exhausted(endToEndId, attempt, null);
                }
            }
            pause();
        }
        throw exhausted(endToEndId, maxAttempts, null);
    }

    private void logTransient(String endToEndId, int attempt, Integer status, String error) {
        log.warn("The participant did not accept the inbound Pix on this attempt, the outcome is UNKNOWN "
                        + "so the rail will re-present the SAME endToEndId — which is what the participant's "
                        + "dedupe has to survive | endToEndId={} attempt={} of {} status={} error={}",
                endToEndId, attempt, maxAttempts, status, error);
    }

    private InboundDeliveryFailedException exhausted(String endToEndId, int attempts, Integer status) {
        log.error("The rail gave up delivering an inbound Pix after exhausting its attempts, the payment "
                        + "was NOT delivered | endToEndId={} attempts={} lastStatus={}",
                endToEndId, attempts, status);
        return new InboundDeliveryFailedException(
                "the participant did not accept the delivery after " + attempts + " attempts",
                false, attempts, status);
    }

    /** Space the retries out, the way a rail does. Interruption ends the wait, never the delivery loop. */
    private void pause() {
        if (retryDelayMs == 0) {
            return;
        }
        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
