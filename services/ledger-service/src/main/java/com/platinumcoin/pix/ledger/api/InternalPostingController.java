package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.usecase.PostDoubleEntryUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code POST /internal/ledger/postings} — the platform's only way to move money
 * (ADR-0006: the ledger is the sole writer of {@code pix_ledger}).
 *
 * <p><b>Why this endpoint may name both accounts</b>, when the platform's first safety rule is that
 * the debited account comes from the JWT: that rule binds the endpoint a *client* can reach, which is
 * `POST /v1/payments/pix` in payment-service (step 18) — there the debtor is derived from the token
 * and the request body has no field for it. This is the internal seam behind it, where the accounts
 * are the operation's parameters by construction; making them explicit is also what lets step 52
 * shard the clearing account without touching a single caller. Like the balance read, it requires a
 * valid token ({@code /internal/**} is deliberately absent from {@code jwt.public-paths}) and a
 * deployed posture would gate it with a service credential or mTLS instead (step-45 hardening).
 *
 * <p><b>No {@code Idempotency-Key} header here.</b> The ledger's idempotency key is the {@code txId}
 * in the body: it is the identity of the posting itself, durable in the table, and shared by every
 * caller that retries — not an HTTP-level de-duplication of one client's request (which is what
 * payment-service adds on top, per ADR-0002).
 *
 * <p>Per ADR-0011 this class does exactly three things: bind and bean-validate the wire shape, call
 * one use case, map the result — every decision lives behind {@link PostDoubleEntryUseCase} and
 * {@link LedgerExceptionHandler}.
 */
@RestController
@RequestMapping("/internal/ledger/postings")
public class InternalPostingController {

    private final PostDoubleEntryUseCase postDoubleEntry;

    public InternalPostingController(PostDoubleEntryUseCase postDoubleEntry) {
        this.postDoubleEntry = postDoubleEntry;
    }

    /**
     * Answers {@code 200} for both a fresh posting and an idempotent replay — see
     * {@link PostingResponse#replayed()} for why the two are not different statuses.
     */
    @PostMapping
    public PostingResponse post(@Valid @RequestBody PostingRequest request) {
        return PostingResponse.from(postDoubleEntry.execute(request.toCommand()));
    }
}
