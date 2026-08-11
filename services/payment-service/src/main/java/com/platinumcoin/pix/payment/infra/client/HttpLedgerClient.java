package com.platinumcoin.pix.payment.infra.client;

import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The only place HTTP touches the ledger (ADR-0010). Implements {@link LedgerClient} by calling
 * ledger-service's {@code POST /internal/ledger/postings} — the platform's single money-moving
 * operation (ADR-0006). payment-service hands both legs and the {@code txId}; the ledger commits the
 * debit and credit in one {@code TransactWriteItems} (Domain Safety Rule #4).
 *
 * <h2>Mapping the ledger's error contract onto the send flow</h2>
 * <ul>
 *   <li>{@code 422 INSUFFICIENT_FUNDS} → {@link InsufficientFundsException} — a business refusal; no
 *       money moved (the guard is inside the ledger transaction), so the caller releases its
 *       daily-limit reservation.</li>
 *   <li>{@code 503 LEDGER_CONFLICT}, a connect/read timeout, or an unreachable host →
 *       {@link LedgerUnavailableException} — nothing debited, safe to retry the same {@code txId}.</li>
 *   <li>Any other ledger response ({@code 404}/{@code 409}/{@code 400}/{@code 422 INVALID_POSTING}) is
 *       unexpected for a well-formed posting whose accounts were just resolved and whose {@code txId} is
 *       a fresh UUID. It is surfaced as {@link LedgerUnavailableException} with the real status/code
 *       logged, rather than guessed at — the operator sees the truth and the client is told to retry,
 *       which the {@code txId}-keyed idempotency makes safe.</li>
 * </ul>
 *
 * <p><b>Two operations, one call site (step 27).</b> An internal transfer credits the payee
 * ({@code PIX_INTERNAL}); an external send credits the clearing account the caller names
 * ({@code PIX_OUT}, money in flight to BACEN). Both go through the same private {@code post}, so the
 * atomicity, the {@code txId} guard and the error mapping cannot drift between the two flows.
 *
 * <p><b>Timeouts.</b> Connect and read timeouts are set so a hung ledger surfaces as a timeout (a
 * {@link ResourceAccessException}) → {@code 503}, rather than pinning the request thread. A deployed
 * build would additionally trip a <b>circuit breaker</b> after repeated failures instead of hammering a
 * struggling ledger — that seam is Sprint 7 / step 32 and is deferred here (Task 3).
 *
 * <p><b>Service-to-service auth.</b> The endpoint is behind the shared JWT filter, so the caller's
 * bearer token is forwarded (ADR-0007; a service credential is the deployed posture, step-45). The
 * correlation id is propagated by common-lib's {@code RestClient} customizer.
 */
@Component
public class HttpLedgerClient implements LedgerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLedgerClient.class);

    private static final String ENTRY_TYPE_PIX_INTERNAL = "PIX_INTERNAL";

    /**
     * Why an external send's money moves: out of the payer, into clearing, on its way to another PSP.
     * The ledger's {@code entryType} vocabulary is an open string that grows with each flow, and it
     * lives here — the domain expresses the intent, {@code infra/} speaks the ledger's language.
     */
    private static final String ENTRY_TYPE_PIX_OUT = "PIX_OUT";

    private final RestClient restClient;

    /** Wire shape of a ledger posting request — mirrors ledger-service's {@code PostingRequest}. */
    record PostingRequest(
            String txId,
            String debitAccount,
            String creditAccount,
            long amountCents,
            String entryType,
            String description) {
    }

    /** Just enough of the problem+json body to read the {@code code} that discriminates a 422. */
    record ProblemView(String code) {
    }

    public HttpLedgerClient(
            RestClient.Builder builder,
            @Value("${services.ledger-service.base-url}") String baseUrl,
            @Value("${services.ledger-service.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${services.ledger-service.read-timeout-ms:3000}") long readTimeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public void postInternalTransfer(
            String txId,
            String debtorAccountId,
            String creditorAccountId,
            long amountCents,
            String description) {
        post(txId, debtorAccountId, creditorAccountId, amountCents, ENTRY_TYPE_PIX_INTERNAL, description);
    }

    @Override
    public void postExternalDebitToClearing(
            String txId,
            String debtorAccountId,
            String clearingAccountId,
            long amountCents,
            String description) {
        // Same endpoint, same atomic TransactWriteItems, same txId guard — only the credit account and
        // the entryType differ. The clearing id arrives as an argument (step 52 shards it), never as a
        // constant of this adapter.
        post(txId, debtorAccountId, clearingAccountId, amountCents, ENTRY_TYPE_PIX_OUT, description);
    }

    /**
     * The one place the posting call is made, for both flows: build the request, map the ledger's error
     * contract onto the send flow's exceptions. Keeping it single means the debit of an external send
     * cannot drift from the debit of an internal one — they are the same operation with a different
     * credit leg.
     */
    private void post(
            String txId,
            String debitAccount,
            String creditAccount,
            long amountCents,
            String entryType,
            String description) {
        PostingRequest body = new PostingRequest(
                txId, debitAccount, creditAccount, amountCents, entryType, description);
        log.debug("POST /internal/ledger/postings | txId={} debitAccount={} creditAccount={} "
                + "amountCents={} entryType={}", txId, debitAccount, creditAccount, amountCents,
                entryType);
        try {
            restClient.post()
                    .uri("/internal/ledger/postings")
                    .headers(this::forwardAuthorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Ledger committed the posting | txId={} debitAccount={} creditAccount={} "
                    + "amountCents={} entryType={}", txId, debitAccount, creditAccount, amountCents,
                    entryType);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String code = problemCode(e);
            if (status == HttpStatus.UNPROCESSABLE_ENTITY.value() && "INSUFFICIENT_FUNDS".equals(code)) {
                log.warn("Ledger refused the debit for insufficient funds | txId={} debitAccount={} "
                        + "amountCents={} entryType={}", txId, debitAccount, amountCents, entryType);
                throw new InsufficientFundsException();
            }
            if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                log.warn("Ledger returned 503 (lost to contention past its retry budget), the send is "
                        + "retry-safe | txId={} code={}", txId, code);
                throw new LedgerUnavailableException("ledger returned 503 " + code, e);
            }
            // Unexpected for a well-formed internal transfer: log the truth, tell the client to retry.
            log.warn("Ledger posting failed with an unexpected status, treating as unavailable | "
                    + "txId={} status={} code={}", txId, status, code);
            throw new LedgerUnavailableException(
                    "ledger posting failed with status " + status + " code " + code, e);
        } catch (ResourceAccessException e) {
            // Connect/read timeout or unreachable host — nothing debited, safe to retry the same txId.
            log.warn("Ledger unreachable or timed out, the send is retry-safe | txId={} error={}",
                    txId, e.getMessage());
            throw new LedgerUnavailableException("ledger unreachable or timed out", e);
        }
    }

    /** Read the {@code code} field of the problem+json error body, or {@code null} if unreadable. */
    private static String problemCode(RestClientResponseException e) {
        try {
            ProblemView problem = e.getResponseBodyAs(ProblemView.class);
            return problem == null ? null : problem.code();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Copy the current request's Authorization header onto the outbound call, if present. */
    private void forwardAuthorization(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String authorization = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (StringUtils.hasText(authorization)) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
    }
}
