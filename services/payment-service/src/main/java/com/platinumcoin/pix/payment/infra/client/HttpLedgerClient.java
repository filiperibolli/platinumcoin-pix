package com.platinumcoin.pix.payment.infra.client;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.security.ServiceTokenIssuer;
import com.platinumcoin.pix.payment.domain.exception.BalanceNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.exception.InvalidStatementCursorException;
import com.platinumcoin.pix.payment.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.payment.domain.model.Direction;
import com.platinumcoin.pix.payment.domain.model.LedgerOutcome;
import com.platinumcoin.pix.payment.domain.model.StatementLine;
import com.platinumcoin.pix.payment.domain.model.StatementPage;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The only place HTTP touches the ledger (ADR-0010). Implements {@link LedgerClient} by calling
 * ledger-service's {@code POST /internal/ledger/postings} — the platform's single money-moving
 * operation (ADR-0006). payment-service hands both legs and the {@code txId}; the ledger commits the
 * debit and credit in one {@code TransactWriteItems} (Domain Safety Rule #4).
 *
 * <h2>Classifying the ledger's answer into a {@link LedgerOutcome} (step 66, ADR-0015)</h2>
 * The one question this adapter must answer honestly is <i>did the money move?</i>, and it has three
 * possible answers, not two. It classifies; it never guesses.
 * <ul>
 *   <li>{@code 200} → {@link LedgerOutcome#POSTED}, or {@link LedgerOutcome#REPLAYED} when the body's
 *       {@code replayed} flag says the ledger already held this {@code txId}. Reading that flag is the
 *       whole of the query-before-retry mechanism: the ledger has always answered it, and this client
 *       used to discard the body.</li>
 *   <li>{@code 422 INSUFFICIENT_FUNDS} → classified as {@link LedgerOutcome#INSUFFICIENT_FUNDS} and
 *       translated to {@link InsufficientFundsException} — a business refusal with its own {@code 422}
 *       mapping and a daily-limit release; no money moved, the guard being inside the ledger's own
 *       transaction.</li>
 *   <li>{@code 503 LEDGER_CONFLICT} and every definite {@code 4xx} refusal ({@code 404},
 *       {@code 409 POSTING_TXID_MISMATCH}, {@code 400}, {@code 422 INVALID_POSTING}) →
 *       {@link LedgerOutcome#REFUSED}. The ledger answered, so nothing committed. Retry-safe, but not
 *       worth an immediate re-POST: re-sending a request it just rejected cannot change the answer.</li>
 *   <li>A connect/read timeout, an unreachable or reset connection, an unattributable {@code 5xx}, or a
 *       {@code 200} whose body cannot be read → {@link LedgerOutcome#UNKNOWN}. <b>This is the case the
 *       step exists for.</b></li>
 * </ul>
 *
 * <p><b>Two operations, one call site (step 27).</b> An internal transfer credits the payee
 * ({@code PIX_INTERNAL}); an external send credits the clearing account the caller names
 * ({@code PIX_OUT}, money in flight to BACEN). Both go through the same private {@code post}, so the
 * atomicity, the {@code txId} guard and the error mapping cannot drift between the two flows.
 *
 * <p><b>Timeouts are an unknown result, never a claim that nothing happened.</b> Connect and read
 * timeouts are set so a hung ledger surfaces as a {@link ResourceAccessException} rather than pinning
 * the request thread — that part is unchanged. What changed in step 66 is the meaning attached to it:
 * this adapter used to map it to {@link LedgerUnavailableException} under the comment <i>"nothing
 * debited, safe to retry the same txId"</i>, and both halves were false. A read timeout means the
 * response did not arrive within the budget; the {@code TransactWriteItems} on the other side may have
 * committed a microsecond before the socket gave up. So the honest classification is
 * {@link LedgerOutcome#UNKNOWN}, and the resolution — re-POSTing the same {@code txId} until the ledger
 * either commits it or reports it as a replay — belongs to the use case, which is the only layer that
 * knows what an ambiguous debit means for a payment. A deployed build would additionally trip a
 * <b>circuit breaker</b> after repeated failures instead of hammering a struggling ledger — that seam
 * is Sprint 7 / step 32 and is deferred here (Task 3).
 *
 * <p><b>Service-to-service auth (step 68, ADR-0017).</b> Every call mints its <b>own</b> short-lived
 * token, addressed to {@code ledger-service} and scoped to {@code ledger:post / ledger:read} alone, via the shared
 * {@link ServiceTokenIssuer}. It used to forward the end user's bearer instead — which is why any
 * user's login was a working credential on the ledger's posting endpoint. The user has not vanished
 * from the call, only their <i>authority</i> has: their id rides along in
 * {@code X-PlatinumCoin-On-Behalf-Of} as evidence for the logs and the audit trail, read by nothing.
 * The correlation id is propagated by common-lib's {@code RestClient} customizer.
 *
 * <p>The two scopes are not interchangeable: the balance and statement reads mint {@code ledger:read},
 * the posting mints {@code ledger:post}. Nothing forces that split at this end — it would be one
 * constant either way — and that is precisely why it is worth writing down: a compromised read path
 * holds a credential that cannot move money.
 */
@Component
public class HttpLedgerClient implements LedgerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLedgerClient.class);

    private static final String ENTRY_TYPE_PIX_INTERNAL = "PIX_INTERNAL";

    /** The ledger's own "I lost to contention and did not commit" code — a definite refusal, not doubt. */
    private static final String LEDGER_CONFLICT_CODE = "LEDGER_CONFLICT";

    /**
     * Why an external send's money moves: out of the payer, into clearing, on its way to another PSP.
     * The ledger's {@code entryType} vocabulary is an open string that grows with each flow, and it
     * lives here — the domain expresses the intent, {@code infra/} speaks the ledger's language.
     */
    private static final String ENTRY_TYPE_PIX_OUT = "PIX_OUT";

    private final RestClient restClient;
    private final ServiceTokenIssuer serviceTokens;

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

    /**
     * Just enough of ledger-service's {@code PostingResponse} to classify the answer: whether this call
     * committed the money or replayed a {@code txId} the ledger already held, and — for the replay case
     * — when the money actually moved. The money fields are deliberately not bound: the caller handed
     * the ledger the amount, so reading it back would invite a second, redundant source for it.
     *
     * @param postedAt the instant the posting committed, which on a replay is the <b>earlier</b> call's
     *                 instant — the one fact a caller surprised by a replay needs in its log
     */
    record PostingView(String txId, boolean replayed, String postedAt) {
    }

    /**
     * Just the integer-cents field of ledger-service's balance response. The ledger ships the amount
     * twice — a decimal string for humans and {@code balanceCents} for services doing arithmetic — and
     * a service that parsed the string back into cents would be re-deriving what it was already given
     * (and inventing a rounding decision on the way).
     */
    record BalanceView(long balanceCents) {
    }

    /**
     * Just enough of ledger-service's {@code StatementResponse} (step 16) to serve this seam: the
     * decimal {@code amount} and {@code entryType} the ledger also ships are for a human reading the
     * internal endpoint directly, and this service already has {@code amountCents} to reformat itself
     * at its own {@code api/} edge — carrying the decimal string too would be a second, redundant
     * source of the same money value.
     */
    record EntriesView(List<EntryView> entries, String nextCursor) {
    }

    record EntryView(
            String txId, Direction direction, long amountCents, String counterpartAccountId,
            String timestamp) {
        StatementLine toDomain() {
            return new StatementLine(txId, direction, amountCents, counterpartAccountId, timestamp);
        }
    }

    public HttpLedgerClient(
            RestClient.Builder builder,
            @Value("${services.ledger-service.base-url}") String baseUrl,
            @Value("${services.ledger-service.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${services.ledger-service.read-timeout-ms:3000}") long readTimeoutMs,
            ServiceTokenIssuer serviceTokens) {
        this.serviceTokens = serviceTokens;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public LedgerOutcome postInternalTransfer(
            String txId,
            String debtorAccountId,
            String creditorAccountId,
            long amountCents,
            String description) {
        return post(txId, debtorAccountId, creditorAccountId, amountCents, ENTRY_TYPE_PIX_INTERNAL,
                description);
    }

    @Override
    public LedgerOutcome postExternalDebitToClearing(
            String txId,
            String debtorAccountId,
            String clearingAccountId,
            long amountCents,
            String description) {
        // Same endpoint, same atomic TransactWriteItems, same txId guard — only the credit account and
        // the entryType differ. The clearing id arrives as an argument (step 52 shards it), never as a
        // constant of this adapter.
        return post(txId, debtorAccountId, clearingAccountId, amountCents, ENTRY_TYPE_PIX_OUT,
                description);
    }

    /**
     * The read half of the seam (step 40): {@code GET /internal/ledger/accounts/{id}/balance}, the
     * strongly-consistent source the balance cache falls back to on a miss.
     *
     * <p><b>Its error mapping is not the posting's.</b> A {@code 404} here is an ordinary business
     * answer — that account has no ledger balance — and becomes {@link BalanceNotFoundException} → a
     * {@code 404} to the client, never a {@code 503} that would invite an endless retry of a question
     * whose answer will not change. Everything else (timeout, unreachable, unexpected status) is
     * {@link LedgerUnavailableException}: the number is unknown, so the service says so rather than
     * serving a zero it made up.
     */
    @Override
    public long readBalanceCents(String accountId) {
        log.debug("GET /internal/ledger/accounts/{}/balance", accountId);
        try {
            BalanceView balance = restClient.get()
                    .uri("/internal/ledger/accounts/{accountId}/balance", accountId)
                    .headers(h -> serviceTokens.authorize(h, InternalApi.AUD_LEDGER,
                            InternalApi.SCOPE_LEDGER_READ))
                    .retrieve()
                    .body(BalanceView.class);
            if (balance == null) {
                log.warn("Ledger answered the balance read with an empty body, treating as unavailable "
                        + "| accountId={}", accountId);
                throw new LedgerUnavailableException("ledger returned an empty balance body");
            }
            log.info("Ledger answered the balance read | accountId={} balanceCents={}",
                    accountId, balance.balanceCents());
            return balance.balanceCents();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == HttpStatus.NOT_FOUND.value()) {
                log.warn("Ledger holds no balance for this account, returning 404 | accountId={} code={}",
                        accountId, problemCode(e));
                throw new BalanceNotFoundException("no ledger account found for id " + accountId);
            }
            log.warn("Ledger balance read failed with an unexpected status, treating as unavailable | "
                    + "accountId={} status={} code={}", accountId, status, problemCode(e));
            throw new LedgerUnavailableException(
                    "ledger balance read failed with status " + status, e);
        } catch (ResourceAccessException e) {
            log.warn("Ledger unreachable or timed out on a balance read | accountId={} error={}",
                    accountId, e.getMessage());
            throw new LedgerUnavailableException("ledger unreachable or timed out", e);
        }
    }

    /**
     * The other read half of the seam (step 41): {@code GET /internal/ledger/accounts/{id}/entries},
     * ledger-service's paginated statement (step 16). {@code limit} is already the use case's effective
     * value — this adapter clamps nothing — and {@code cursor} travels as an opaque query string; this
     * service never decodes it, because it is an AWS key only the ledger can interpret.
     *
     * <p><b>Error mapping is its own, distinct from both the posting's and the balance read's.</b> A
     * {@code 400 INVALID_CURSOR} is a well-formed refusal of a malformed or cross-account token
     * (ledger-service's own re-assertion of Domain Safety Rule #1) and becomes
     * {@link InvalidStatementCursorException} → a {@code 400} to the client, never a {@code 503} that
     * would invite it to retry the same bad cursor forever. Everything else unexpected is
     * {@link LedgerUnavailableException}, exactly like a balance-read failure.
     */
    @Override
    public StatementPage readStatement(String accountId, String cursor, int limit) {
        boolean hasCursor = StringUtils.hasText(cursor);
        log.debug("GET /internal/ledger/accounts/{}/entries | hasCursor={} limit={}",
                accountId, hasCursor, limit);
        try {
            EntriesView view = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/ledger/accounts/{accountId}/entries")
                            .queryParamIfPresent("cursor", Optional.ofNullable(cursor)
                                    .filter(StringUtils::hasText))
                            .queryParam("limit", limit)
                            .build(accountId))
                    .headers(h -> serviceTokens.authorize(h, InternalApi.AUD_LEDGER,
                            InternalApi.SCOPE_LEDGER_READ))
                    .retrieve()
                    .body(EntriesView.class);
            if (view == null) {
                log.warn("Ledger answered the statement read with an empty body, treating as "
                        + "unavailable | accountId={}", accountId);
                throw new LedgerUnavailableException("ledger returned an empty statement body");
            }
            List<StatementLine> lines = view.entries().stream().map(EntryView::toDomain).toList();
            log.info("Ledger answered the statement read | accountId={} entries={} hasNextPage={}",
                    accountId, lines.size(), view.nextCursor() != null);
            return new StatementPage(lines, view.nextCursor());
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String code = problemCode(e);
            if (status == HttpStatus.BAD_REQUEST.value() && "INVALID_CURSOR".equals(code)) {
                log.warn("Ledger refused the statement cursor, returning 400 | accountId={} code={}",
                        accountId, code);
                throw new InvalidStatementCursorException(
                        "the pagination cursor is invalid or does not belong to account " + accountId);
            }
            log.warn("Ledger statement read failed with an unexpected status, treating as unavailable "
                    + "| accountId={} status={} code={}", accountId, status, code);
            throw new LedgerUnavailableException(
                    "ledger statement read failed with status " + status, e);
        } catch (ResourceAccessException e) {
            log.warn("Ledger unreachable or timed out on a statement read | accountId={} error={}",
                    accountId, e.getMessage());
            throw new LedgerUnavailableException("ledger unreachable or timed out", e);
        }
    }

    /**
     * The one place the posting call is made, for both flows: build the request and <b>classify</b> what
     * came back. Keeping it single means the debit of an external send cannot drift from the debit of an
     * internal one — they are the same operation with a different credit leg — and, since step 66, that
     * the two cannot end up holding different theories of a timeout either.
     *
     * <p>Every path out of here is a classification of one question: <i>did the money move?</i> The only
     * answer this method is forbidden to invent is a confident one it does not have.
     */
    private LedgerOutcome post(
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
            PostingView view = restClient.post()
                    .uri("/internal/ledger/postings")
                    .headers(h -> serviceTokens.authorize(h, InternalApi.AUD_LEDGER,
                            InternalApi.SCOPE_LEDGER_POST))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PostingView.class);
            if (view == null) {
                // A 2xx whose body we cannot read: the ledger almost certainly committed, but "almost
                // certainly" is not a fact about money. Unknown, and resolved by re-posting the txId.
                log.warn("Ledger answered the posting with an empty body, so whether it committed is "
                                + "unknown | txId={} debitAccount={} amountCents={} entryType={}",
                        txId, debitAccount, amountCents, entryType);
                return LedgerOutcome.UNKNOWN;
            }
            if (view.replayed()) {
                log.info("Ledger replayed a posting it already held under this txId, no money moved on "
                                + "this call | txId={} debitAccount={} creditAccount={} amountCents={} "
                                + "entryType={} originallyPostedAt={}",
                        txId, debitAccount, creditAccount, amountCents, entryType, view.postedAt());
                return LedgerOutcome.REPLAYED;
            }
            log.info("Ledger committed the posting | txId={} debitAccount={} creditAccount={} "
                    + "amountCents={} entryType={}", txId, debitAccount, creditAccount, amountCents,
                    entryType);
            return LedgerOutcome.POSTED;
        } catch (RestClientResponseException e) {
            return classifyErrorResponse(e, txId, debitAccount, amountCents, entryType);
        } catch (ResourceAccessException e) {
            // A connect timeout or a dropped connection. This used to read "nothing debited, safe to
            // retry the same txId" — a belief the wire cannot support: the response failing to arrive
            // says nothing about whether the TransactWriteItems committed on the other side. The only
            // honest classification is UNKNOWN; resolving it is the use case's job (ADR-0015).
            log.warn("Ledger unreachable or timed out, so whether the posting committed is UNKNOWN and "
                            + "must be resolved by re-posting the same txId | txId={} debitAccount={} "
                            + "amountCents={} entryType={} error={}",
                    txId, debitAccount, amountCents, entryType, e.getMessage());
            return LedgerOutcome.UNKNOWN;
        } catch (RestClientException e) {
            // Everything else the client can fail with, and it is a broader net than it looks: now that
            // the body is BOUND rather than discarded, a READ timeout surfaces here (the wait expires
            // while extracting the response) instead of as a ResourceAccessException — a behaviour
            // change that came free with reading `replayed`, and one HttpLedgerClientTest pins. Any
            // failure to obtain a readable answer means the same thing, so it classifies the same way.
            log.warn("The ledger's answer could not be obtained or read, so whether the posting "
                            + "committed is UNKNOWN and must be resolved by re-posting the same txId | "
                            + "txId={} debitAccount={} amountCents={} entryType={} error={}",
                    txId, debitAccount, amountCents, entryType, e.getMessage());
            return LedgerOutcome.UNKNOWN;
        }
    }

    /**
     * Classify a non-2xx answer. The split that matters is <b>definite refusal vs. unattributable</b>:
     * a {@code 4xx} or the ledger's own {@code 503 LEDGER_CONFLICT} means it looked at the posting and
     * declined to commit it, so the money certainly did not move; a {@code 5xx} it did not author (a
     * proxy's {@code 502}/{@code 503}, an unhandled {@code 500}) could equally have been produced after
     * the write committed, so it is UNKNOWN like a timeout.
     */
    private LedgerOutcome classifyErrorResponse(
            RestClientResponseException e, String txId, String debitAccount, long amountCents,
            String entryType) {
        int status = e.getStatusCode().value();
        String code = problemCode(e);
        if (status == HttpStatus.UNPROCESSABLE_ENTITY.value() && "INSUFFICIENT_FUNDS".equals(code)) {
            log.warn("Ledger refused the debit for insufficient funds | txId={} debitAccount={} "
                    + "amountCents={} entryType={}", txId, debitAccount, amountCents, entryType);
            // Classified as an outcome, then translated: this refusal carries its own 422 mapping and a
            // daily-limit release, so it stays an exception rather than a value every caller must check.
            throw new InsufficientFundsException();
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value() && LEDGER_CONFLICT_CODE.equals(code)) {
            log.warn("Ledger returned 503 LEDGER_CONFLICT (lost to contention past its retry budget), it "
                            + "definitively did not commit and the send is retry-safe | txId={} "
                            + "amountCents={} entryType={}", txId, amountCents, entryType);
            return LedgerOutcome.REFUSED;
        }
        if (e.getStatusCode().is4xxClientError()) {
            // Unexpected for a well-formed posting whose accounts were just resolved, but definite: the
            // ledger parsed the request and declined it, so nothing committed. Logged with the real
            // status/code rather than guessed at — the operator sees the truth.
            log.warn("Ledger refused the posting with an unexpected client error, nothing committed | "
                            + "txId={} debitAccount={} amountCents={} entryType={} status={} code={}",
                    txId, debitAccount, amountCents, entryType, status, code);
            return LedgerOutcome.REFUSED;
        }
        log.warn("Ledger posting failed with a server error it may or may not have authored, so whether "
                        + "it committed is UNKNOWN | txId={} debitAccount={} amountCents={} entryType={} "
                        + "status={} code={}",
                txId, debitAccount, amountCents, entryType, status, code);
        return LedgerOutcome.UNKNOWN;
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
