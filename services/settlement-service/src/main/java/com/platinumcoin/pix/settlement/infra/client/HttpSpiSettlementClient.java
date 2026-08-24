package com.platinumcoin.pix.settlement.infra.client;

import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
import com.platinumcoin.pix.settlement.domain.model.SpiReconciliation;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import com.platinumcoin.pix.common.tracing.ForceSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The only place HTTP touches the settlement rail (ADR-0010): {@code POST /spi/settlements} against
 * mock-bacen.
 *
 * <h2>The 12s read timeout is a decision, not a default</h2>
 * BACEN settles in up to 10s (ADR-0003), so a budget below that would abandon perfectly healthy
 * settlements; a budget far above it would let one hung call hold an SQS message hostage past its 30s
 * visibility timeout, at which point SQS redelivers a message still being worked on and two workers race
 * on one transaction. 12s sits between the two: generous enough for the slowest honest answer, tight
 * enough to finish (and delete or release) well inside the visibility window. This is the <b>opposite
 * posture</b> to the 200ms fraud budget (ADR-0005): nobody is waiting here — a user got their
 * {@code 202} seconds ago — so patience is cheap and giving up early is what costs.
 *
 * <h2>Three answers, three types</h2>
 * <ul>
 *   <li><b>2xx</b> → a {@link SpiSettlement}. The money moved.</li>
 *   <li><b>422</b> → {@link SpiSettlementRejectedException}. BACEN looked and said no, permanently;
 *       retrying cannot change it (step 33 reverses).</li>
 *   <li><b>everything else</b> — {@code 503}, {@code 504}, a connection failure, the read timeout, an
 *       unreadable body → {@link SpiCallFailedException}, meaning <b>unknown</b>. Never "failed": a
 *       timeout may well have settled (mock-bacen's injection settles and then withholds the answer, as
 *       a real rail can), which is why step 32 must <i>ask</i> before retrying.</li>
 * </ul>
 * Collapsing the last two into one type is the classic way a refused transfer ends up retried forever or
 * a timed-out one ends up reversed while the money is gone. The type system keeps them apart.
 *
 * <p><b>No {@code Authorization} header, on purpose.</b> BACEN is outside PlatinumCoin's trust domain
 * and validates none of our tokens (a real participant presents mTLS + an ICP-Brasil certificate), so —
 * like account-service's DICT client — this outbound call forwards no bearer token. The correlation id
 * still rides along via common-lib's {@code RestClient} customizer, so one {@code grep} spans the send,
 * the settlement and the SPI.
 */
@Component
public class HttpSpiSettlementClient implements SpiSettlementClient {

    private static final Logger log = LoggerFactory.getLogger(HttpSpiSettlementClient.class);

    /** The only status a 2xx body may carry; anything else is an answer we do not understand. */
    private static final String SETTLED = "SETTLED";
    /** The rail's two other verdicts, read by the reconciliation query (step 35). */
    private static final String FAILED = "FAILED";
    private static final String UNKNOWN = "UNKNOWN";

    private final RestClient restClient;

    /** Wire shape of what we send — mock-bacen's {@code SettlementRequest}. Integer cents. */
    record SettleRequest(String endToEndId, String creditorKey, long amountCents, String debtorIspb,
            String description) {
    }

    /** Wire shape of what we get back — mock-bacen's {@code SettlementView}. */
    record SettlementView(String endToEndId, String status, Long amountCents, String creditorKey,
            String creditorIspb, String rejectionReason, Instant recordedAt) {
    }

    public HttpSpiSettlementClient(
            RestClient.Builder builder,
            @Value("${services.bacen.base-url}") String baseUrl,
            @Value("${services.bacen.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${services.bacen.read-timeout-ms}") long readTimeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        log.info("SPI settlement client ready, external Pix will be settled against this rail | "
                        + "baseUrl={} connectTimeoutMs={} readTimeoutMs={}",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    @Override
    public SpiSettlement settle(String endToEndId, String creditorKey, long amountCents,
            String description, String debtorIspb) {
        log.info("Asking the SPI to settle a Pix | endToEndId={} creditorKey={} amountCents={} "
                        + "debtorIspb={}",
                endToEndId, creditorKey, amountCents, debtorIspb);

        SettlementView view;
        try {
            view = restClient.post()
                    .uri("/spi/settlements")
                    .body(new SettleRequest(endToEndId, creditorKey, amountCents, debtorIspb, description))
                    .retrieve()
                    .body(SettlementView.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
                throw rejected(endToEndId, e);
            }
            // 503, 504 and anything else: the rail's state is unknown to us.
            throw failed(endToEndId, "the SPI answered " + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            // Connection refused, DNS failure, the 12s read timeout expiring.
            throw failed(endToEndId, "the SPI could not be reached or did not answer in time", e);
        }

        if (view == null || !SETTLED.equals(view.status()) || view.amountCents() == null) {
            // A 2xx we cannot read is not a settlement. Treated as unknown rather than as success:
            // reporting a payment settled on the strength of an unparseable body is the one mistake
            // this whole flow must not make.
            log.warn("The SPI answered 2xx with a body this client cannot read as a settlement, treating "
                            + "the outcome as UNKNOWN | endToEndId={} body={}", endToEndId, view);
            throw new SpiCallFailedException(
                    "the SPI returned an answer that is not a readable settlement", null);
        }

        log.info("The SPI settled the Pix | endToEndId={} amountCents={} creditorIspb={} recordedAt={}",
                view.endToEndId(), view.amountCents(), view.creditorIspb(), view.recordedAt());
        return new SpiSettlement(view.endToEndId(), view.amountCents(), view.creditorIspb(),
                view.recordedAt());
    }

    @Override
    public Optional<SpiSettlement> findSettlement(String endToEndId) {
        log.info("Querying the SPI for the fate of a settlement before retrying it | endToEndId={}",
                endToEndId);

        SettlementView view;
        try {
            view = restClient.get()
                    .uri("/spi/settlements/{endToEndId}", endToEndId)
                    .retrieve()
                    .body(SettlementView.class);
        } catch (RuntimeException e) {
            // The query itself failed — a status lookup we could not complete. Reported as "not known to
            // be settled" (empty), never as a failure: the caller then retries the idempotent POST, which
            // is the safe fallback. WARN, not ERROR: an unavailable dependency is a degradation the flow
            // absorbs.
            log.warn("The SPI status query did not answer, treating the settlement as not-yet-known and "
                            + "letting the retry POST proceed | endToEndId={} error={}",
                    endToEndId, e.toString());
            return Optional.empty();
        }

        if (view == null || !SETTLED.equals(view.status()) || view.amountCents() == null) {
            // UNKNOWN (the rail never heard of it), a refusal, or an unreadable body — none of which is a
            // settlement to finalize on. Empty means "go ahead and retry the POST".
            log.info("The SPI does not report this id as settled yet, the retry POST will proceed | "
                            + "endToEndId={} status={}", endToEndId, view == null ? null : view.status());
            return Optional.empty();
        }

        log.info("The SPI reports this Pix ALREADY settled, the caller can finalize without re-sending | "
                        + "endToEndId={} amountCents={} creditorIspb={} recordedAt={}",
                view.endToEndId(), view.amountCents(), view.creditorIspb(), view.recordedAt());
        return Optional.of(new SpiSettlement(view.endToEndId(), view.amountCents(), view.creditorIspb(),
                view.recordedAt()));
    }

    @Override
    public SpiReconciliation reconcile(String endToEndId) {
        log.info("Reconciliation is querying the SPI for the definitive fate of a stuck settlement | "
                + "endToEndId={}", endToEndId);

        SettlementView view;
        try {
            view = restClient.get()
                    .uri("/spi/settlements/{endToEndId}", endToEndId)
                    .retrieve()
                    .body(SettlementView.class);
        } catch (RuntimeException e) {
            // The status query itself could not be completed. Reported as UNREACHABLE — nothing is
            // decided, the resolver leaves the transaction for the next cycle. WARN, not ERROR: an
            // unavailable dependency is a degradation reconciliation is designed to ride out.
            log.warn("The SPI status query for reconciliation did not answer, treating the rail as "
                            + "unreachable so the transaction is left for the next cycle | endToEndId={} "
                            + "error={}", endToEndId, e.toString());
            return SpiReconciliation.unreachable();
        }

        if (view == null || view.status() == null) {
            // A body we cannot read is not a verdict to act on: reversing or finalizing on it would be
            // deciding money on noise. Treated as unreachable — leave for the next cycle.
            log.warn("The SPI answered the reconciliation query with a body this client cannot read as a "
                    + "status, leaving the transaction for the next cycle | endToEndId={} body={}",
                    endToEndId, view);
            return SpiReconciliation.unreachable();
        }

        return switch (view.status()) {
            case SETTLED -> settledReconciliation(endToEndId, view);
            case FAILED -> {
                String reason = view.rejectionReason() != null ? view.rejectionReason() : "unspecified";
                log.info("Reconciliation found the SPI refused this transfer permanently, it must be "
                        + "reversed | endToEndId={} reason={}", endToEndId, reason);
                yield SpiReconciliation.failed(reason);
            }
            case UNKNOWN -> {
                log.info("Reconciliation found the SPI has no record of this id — the send never landed "
                        + "there | endToEndId={}", endToEndId);
                yield SpiReconciliation.unknown();
            }
            default -> {
                log.warn("The SPI answered the reconciliation query with a status this client does not "
                        + "understand, treating the rail as unreachable | endToEndId={} status={}",
                        endToEndId, view.status());
                yield SpiReconciliation.unreachable();
            }
        };
    }

    /** A SETTLED reconciliation answer, or UNREACHABLE if the settled body is missing its amount. */
    private static SpiReconciliation settledReconciliation(String endToEndId, SettlementView view) {
        if (view.amountCents() == null) {
            log.warn("The SPI reported SETTLED for reconciliation but without an amount, treating it as "
                    + "unreachable rather than finalizing on a fabricated amount | endToEndId={}",
                    endToEndId);
            return SpiReconciliation.unreachable();
        }
        log.info("Reconciliation found the SPI had SETTLED this Pix, it can be finalized | endToEndId={} "
                        + "amountCents={} creditorIspb={} recordedAt={}",
                view.endToEndId(), view.amountCents(), view.creditorIspb(), view.recordedAt());
        return SpiReconciliation.settled(new SpiSettlement(view.endToEndId(), view.amountCents(),
                view.creditorIspb(), view.recordedAt()));
    }

    private static SpiSettlementRejectedException rejected(String endToEndId,
            RestClientResponseException cause) {
        String reason = detailOf(cause);
        // A permanent refusal ends as a REVERSAL — one of the five outcomes ADR-0021 always keeps, and
        // the one a payer will phone about.
        ForceSample.mark("the rail refused this settlement permanently, it will be reversed");
        log.warn("The SPI refused this settlement permanently, retrying it is pointless | endToEndId={} "
                        + "status=422 reason={}", endToEndId, reason);
        return new SpiSettlementRejectedException(reason, cause);
    }

    private static SpiCallFailedException failed(String endToEndId, String what, RuntimeException cause) {
        // WARN, not ERROR: an unavailable dependency is a degradation the flow is designed to absorb,
        // not an actionable fault in this service.
        // An UNKNOWN outcome is the hardest thing this platform handles (ADR-0015/ADR-0016). Whatever the
        // head ratio says, this is a trace someone will want.
        ForceSample.mark("the rail gave no answer, the settlement outcome is UNKNOWN");
        log.warn("The settlement attempt did not produce an answer, the outcome is UNKNOWN and must not "
                        + "be treated as a failure | endToEndId={} what={} error={}",
                endToEndId, what, cause.toString());
        return new SpiCallFailedException(what, cause);
    }

    /**
     * The refusal reason, read from the platform's problem+json body. Best effort by design: the reason
     * is for the log and for step 33's decision, and a rail that refuses without explaining itself must
     * still be recognised as having refused.
     */
    private static String detailOf(RestClientResponseException e) {
        try {
            ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
            if (problem != null && problem.getDetail() != null) {
                return problem.getDetail();
            }
        } catch (RuntimeException ignored) {
            // An error body we cannot parse changes nothing: the refusal itself is the 422.
        }
        return "unspecified";
    }
}
