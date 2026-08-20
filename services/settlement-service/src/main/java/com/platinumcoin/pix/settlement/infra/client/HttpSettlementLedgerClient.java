package com.platinumcoin.pix.settlement.infra.client;

import com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException;
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
 * <h2>Error contract: any non-2xx is "unavailable, retry"</h2>
 * A well-formed posting to the clearing account (a system account exempt from the non-negative guard)
 * against a fresh {@code -rel}/{@code -rev} {@code txId} has no legitimate business rejection — an
 * idempotent replay returns {@code 200}. So anything other than success (a timeout, a {@code 503}, an
 * unexpected status) becomes {@link LedgerUnavailableException}: nothing moved, and the deterministic
 * {@code txId} makes the redelivery's retry a replay, never a double posting.
 */
@Component
public class HttpSettlementLedgerClient implements LedgerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpSettlementLedgerClient.class);

    private static final String ENTRY_TYPE_CLEARING_RELEASE = "CLEARING_RELEASE";
    private static final String ENTRY_TYPE_PIX_REVERSAL = "PIX_REVERSAL";
    private static final String ENTRY_TYPE_PIX_IN = "PIX_IN";

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
    public void releaseClearing(String txId, String clearingAccount, long amountCents, String description) {
        // debit clearing / credit SPI_SETTLED: draw the settled money out of the clearing account.
        post(txId, clearingAccount, settledAccountId, amountCents, ENTRY_TYPE_CLEARING_RELEASE, description);
    }

    @Override
    public void reverseToPayer(String txId, String clearingAccount, String payerAccount, long amountCents,
            String description) {
        // debit clearing / credit payer: return the parked money to the payer.
        post(txId, clearingAccount, payerAccount, amountCents, ENTRY_TYPE_PIX_REVERSAL, description);
    }

    @Override
    public void creditInbound(String txId, String clearingAccount, String payeeAccount, long amountCents,
            String description) {
        // debit clearing / credit the payee: money arriving from the Pix network lands on a user's balance.
        post(txId, clearingAccount, payeeAccount, amountCents, ENTRY_TYPE_PIX_IN, description);
    }

    private void post(String txId, String debitAccount, String creditAccount, long amountCents,
            String entryType, String description) {
        PostingRequest body = new PostingRequest(
                txId, debitAccount, creditAccount, amountCents, entryType, description);
        log.debug("POST /internal/ledger/postings | txId={} debitAccount={} creditAccount={} "
                + "amountCents={} entryType={}", txId, debitAccount, creditAccount, amountCents, entryType);
        try {
            restClient.post()
                    .uri("/internal/ledger/postings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceTokens.issue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Ledger committed the finalization posting | txId={} debitAccount={} "
                    + "creditAccount={} amountCents={} entryType={}",
                    txId, debitAccount, creditAccount, amountCents, entryType);
        } catch (RestClientResponseException e) {
            // Unexpected for a system-account debit against a fresh -rel/-rev txId: log the truth, treat
            // as unavailable so the message is redelivered and the idempotent txId replays the posting.
            log.warn("Ledger finalization posting failed with an unexpected status, treating as "
                    + "unavailable so the message redelivers | txId={} entryType={} status={}",
                    txId, entryType, e.getStatusCode().value());
            throw new LedgerUnavailableException(
                    "ledger finalization posting failed with status " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            // Connect/read timeout or unreachable host — nothing posted, safe to retry the same txId.
            log.warn("Ledger unreachable or timed out during finalization, the message redelivers | "
                    + "txId={} entryType={} error={}", txId, entryType, e.getMessage());
            throw new LedgerUnavailableException("ledger unreachable or timed out", e);
        }
    }
}
