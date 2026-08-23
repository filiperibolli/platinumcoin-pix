package com.platinumcoin.pix.settlement.infra.client;

import com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.settlement.domain.model.LedgerOutcome;
import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import com.platinumcoin.pix.settlement.infra.security.ServiceTokenIssuer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The only place HTTP touches the ledger from settlement-service (ADR-0010). Implements
 * {@link LedgerClient} against ledger-service's {@code POST /internal/ledger/postings} — the platform's
 * one money-moving operation (ADR-0006) — for the two definitive-outcome postings of step 33 and the
 * inbound credit of step 37.
 *
 * <h2>The three postings, all idempotent by {@code txId}</h2>
 * <ul>
 *   <li><b>{@code CLEARING_RELEASE}</b> — {@code debit clearing / credit SPI_SETTLED}. On a confirmed
 *       settlement the money left the bank, so the parked clearing balance is drawn back out into the
 *       "settled to the network" account, keeping Σ balances invariant.</li>
 *   <li><b>{@code PIX_REVERSAL}</b> — {@code debit clearing / credit payer}. On a permanent refusal the
 *       money never left, so it goes back to the payer — a new, append-only posting, never an edit.</li>
 *   <li><b>{@code PIX_IN}</b> (step 37) — {@code debit clearing / credit payee}. Money arriving from
 *       another participant enters our books through the same clearing account an outbound send parks
 *       money in — the exact mirror of {@code PIX_OUT}, which debits the payer and credits clearing.</li>
 * </ul>
 * The credit counter-account for a release ({@code SPI_SETTLED}) and the {@code entryType} vocabulary are
 * the ledger's language and live here; the domain named only the accounts it knows.
 *
 * <h2>Auth: a self-minted service token</h2>
 * settlement runs off a queue, so there is no user request whose {@code Authorization} header it could
 * forward (the way payment-service does, step 27). The {@code /internal/**} endpoint is deliberately not
 * public, so this adapter attaches a short-lived service token minted by {@link ServiceTokenIssuer}
 * (signed with the shared secret; the production posture is step-45, ADR-0013). The correlation id is
 * propagated by common-lib's {@code RestClient} customizer, so a reversal's ledger call still greps under
 * the original request id.
 *
 * <h2>Classifying the answer, exactly as payment-service does (step 66, ADR-0015)</h2>
 * A well-formed posting to the clearing account (a system account exempt from the non-negative guard)
 * against a {@code -rel}/{@code -rev} {@code txId} has no legitimate business rejection — an idempotent
 * replay returns {@code 200} with {@code replayed: true}. What this adapter used to do with everything
 * else was collapse it into one {@link LedgerUnavailableException} under the comment <i>"nothing
 * posted"</i>. That sentence was as false here as it was in payment-service: a read timeout says the
 * answer did not arrive, not that the {@code TransactWriteItems} did not commit. So the answer is now
 * classified into a {@link LedgerOutcome} and the domain decides:
 * <ul>
 *   <li>{@code 200} → {@link LedgerOutcome#POSTED}, or {@link LedgerOutcome#REPLAYED} when the body says
 *       the ledger already held this {@code txId} — routine under an at-least-once queue.</li>
 *   <li>A definite refusal ({@code 4xx}, or the ledger's own {@code 503 LEDGER_CONFLICT}) →
 *       {@link LedgerOutcome#REFUSED}.</li>
 *   <li>A timeout, an unreachable host, an unattributable {@code 5xx}, an unreadable body →
 *       {@link LedgerOutcome#UNKNOWN}.</li>
 * </ul>
 * Both non-success outcomes end the same way for the caller — the transition does not run and the
 * message redelivers — but they are not the same fact, they do not read the same in the log, and the
 * one that is doubt is now named as doubt.
 */
@Component
public class HttpSettlementLedgerClient implements LedgerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpSettlementLedgerClient.class);

    private static final String ENTRY_TYPE_CLEARING_RELEASE = "CLEARING_RELEASE";
    private static final String ENTRY_TYPE_PIX_REVERSAL = "PIX_REVERSAL";
    private static final String ENTRY_TYPE_PIX_IN = "PIX_IN";

    /** The ledger's own "I lost to contention and did not commit" code — a definite refusal, not doubt. */
    private static final String LEDGER_CONFLICT_CODE = "LEDGER_CONFLICT";

    private final RestClient restClient;
    private final ServiceTokenIssuer serviceTokens;

    /** The credit leg of a CLEARING_RELEASE — where settled money lands so Σ balances stays invariant. */
    private final String settledAccountId;

    /** Wire shape of a ledger posting request — mirrors ledger-service's {@code PostingRequest}. */
    record PostingRequest(
            String txId,
            String debitAccount,
            String creditAccount,
            long amountCents,
            String entryType,
            String description) {
    }

    /**
     * Just enough of ledger-service's {@code PostingResponse} to classify the answer: whether this call
     * committed the money or replayed a {@code txId} the ledger already held, and when that original
     * posting landed.
     */
    record PostingView(String txId, boolean replayed, String postedAt) {
    }

    /** Just enough of the problem+json body to read the {@code code} that discriminates a 503. */
    record ProblemView(String code) {
    }

    public HttpSettlementLedgerClient(
            RestClient.Builder builder,
            ServiceTokenIssuer serviceTokens,
            @Value("${services.ledger-service.base-url}") String baseUrl,
            @Value("${services.ledger-service.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${services.ledger-service.read-timeout-ms:3000}") long readTimeoutMs,
            @Value("${pix.settlement.settled-account-id:SPI_SETTLED}") String settledAccountId) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.serviceTokens = serviceTokens;
        this.settledAccountId = settledAccountId;
    }

    @Override
    public LedgerOutcome releaseClearing(String txId, String clearingAccount, long amountCents,
            String description) {
        // debit clearing / credit SPI_SETTLED: draw the settled money out of the clearing account.
        return post(txId, clearingAccount, settledAccountId, amountCents, ENTRY_TYPE_CLEARING_RELEASE,
                description);
    }

    @Override
    public LedgerOutcome reverseToPayer(String txId, String clearingAccount, String payerAccount,
            long amountCents, String description) {
        // debit clearing / credit payer: return the parked money to the payer.
        return post(txId, clearingAccount, payerAccount, amountCents, ENTRY_TYPE_PIX_REVERSAL, description);
    }

    @Override
    public LedgerOutcome creditInbound(String txId, String clearingAccount, String payeeAccount,
            long amountCents, String description) {
        // debit clearing / credit the payee: money arriving from the Pix network lands on a user's balance.
        return post(txId, clearingAccount, payeeAccount, amountCents, ENTRY_TYPE_PIX_IN, description);
    }

    /**
     * The one place a finalization posting is made, and the one place its answer is classified — so the
     * release, the reversal and the inbound credit cannot end up holding three theories of a timeout.
     */
    private LedgerOutcome post(String txId, String debitAccount, String creditAccount, long amountCents,
            String entryType, String description) {
        PostingRequest body = new PostingRequest(
                txId, debitAccount, creditAccount, amountCents, entryType, description);
        log.debug("POST /internal/ledger/postings | txId={} debitAccount={} creditAccount={} "
                + "amountCents={} entryType={}", txId, debitAccount, creditAccount, amountCents, entryType);
        try {
            PostingView view = restClient.post()
                    .uri("/internal/ledger/postings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceTokens.issue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PostingView.class);
            if (view == null) {
                log.warn("Ledger answered the finalization posting with an empty body, so whether it "
                                + "committed is unknown | txId={} entryType={} amountCents={}",
                        txId, entryType, amountCents);
                return LedgerOutcome.UNKNOWN;
            }
            if (view.replayed()) {
                // Routine under at-least-once delivery: the deterministic txId turned a redelivery into
                // a replay instead of a second money move, which is the whole reason it is deterministic.
                log.info("Ledger replayed the finalization posting it already held under this txId, no "
                                + "money moved on this call | txId={} entryType={} amountCents={} "
                                + "originallyPostedAt={}",
                        txId, entryType, amountCents, view.postedAt());
                return LedgerOutcome.REPLAYED;
            }
            log.info("Ledger committed the finalization posting | txId={} debitAccount={} "
                    + "creditAccount={} amountCents={} entryType={}",
                    txId, debitAccount, creditAccount, amountCents, entryType);
            return LedgerOutcome.POSTED;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String code = problemCode(e);
            boolean definitivelyRefused = e.getStatusCode().is4xxClientError()
                    || (status == 503 && LEDGER_CONFLICT_CODE.equals(code));
            if (definitivelyRefused) {
                // The ledger looked at the posting and declined it: nothing committed. Unexpected for a
                // system-account debit, so it is logged with the real status/code and left for the
                // redelivery, which re-presents the identical deterministic txId.
                log.warn("Ledger refused the finalization posting, nothing committed and the message "
                                + "redelivers | txId={} entryType={} status={} code={}",
                        txId, entryType, status, code);
                return LedgerOutcome.REFUSED;
            }
            log.warn("Ledger finalization posting failed with a server error it may or may not have "
                            + "authored, so whether it committed is UNKNOWN and the redelivery re-posts "
                            + "the same txId | txId={} entryType={} status={} code={}",
                    txId, entryType, status, code);
            return LedgerOutcome.UNKNOWN;
        } catch (ResourceAccessException e) {
            // This used to read "nothing posted, safe to retry the same txId". The retry half was true —
            // the txId is deterministic — but the "nothing posted" half never was.
            log.warn("Ledger unreachable or timed out during finalization, so whether the posting "
                            + "committed is UNKNOWN; the message redelivers and re-posts the same txId | "
                            + "txId={} entryType={} error={}", txId, entryType, e.getMessage());
            return LedgerOutcome.UNKNOWN;
        } catch (RestClientException e) {
            // Now that the body is BOUND rather than discarded, a READ timeout surfaces here rather than
            // as a ResourceAccessException — same fact, same classification.
            log.warn("The ledger's answer to the finalization posting could not be obtained or read, so "
                            + "whether it committed is UNKNOWN | txId={} entryType={} error={}",
                    txId, entryType, e.getMessage());
            return LedgerOutcome.UNKNOWN;
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
}
