package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.common.security.AuthenticatedUser;
import com.platinumcoin.pix.payment.domain.usecase.SendPixCommand;
import com.platinumcoin.pix.payment.domain.usecase.SendPixOutcome;
import com.platinumcoin.pix.payment.domain.usecase.SendPixUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code POST /v1/payments/pix}. Per ADR-0011 it does exactly three things: bind +
 * bean-validate the wire shape ({@link SendPixRequest} plus the {@code Idempotency-Key} header), call
 * one use case, and map the {@link SendPixOutcome} to HTTP. No policy lives here — id generation, amount
 * parsing, the clock, and the whole idempotency decision are inside {@link SendPixUseCase}.
 *
 * <p><b>The debtor comes from the token.</b> {@link AuthenticatedUser} (injected by common-lib from the
 * validated JWT) supplies {@code accountId}; the request body has no source-account field, so the
 * debited account is inexpressible from the payload (Domain Safety Rule #1).
 *
 * <p><b>Idempotency-Key (Domain Safety Rule #2).</b> The header is REQUIRED. It is read as optional
 * here and handed to the use case, which owns the decision that a missing key is a {@code 400} — the
 * controller applies no policy, it only binds. A first call is accepted ({@code 202}); an identical
 * retry replays the memoized response ({@link SendPixOutcome.Replayed}); the same key with a different
 * body is a {@code 409} (raised in the use case, mapped by {@link PaymentExceptionHandler}).
 */
@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final SendPixUseCase sendPix;

    public PaymentController(SendPixUseCase sendPix) {
        this.sendPix = sendPix;
    }

    @PostMapping("/pix")
    public ResponseEntity<PaymentAcceptedResponse> send(
            @Valid @RequestBody SendPixRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            AuthenticatedUser user) {

        SendPixOutcome outcome = sendPix.execute(new SendPixCommand(
                user.accountId(), request.pixKey(), request.amount(), request.description(), idempotencyKey));

        // A fresh acceptance and an idempotent replay render identically — same status (202), same
        // Location, same body from the same ids — so a client that missed the original 202 gets a
        // byte-identical reply on retry. The status is taken from the outcome (memoized for a replay).
        return ResponseEntity
                .status(outcome.httpStatus())
                .location(URI.create("/v1/payments/" + outcome.transactionId()))
                .body(PaymentAcceptedResponse.of(outcome.transactionId(), outcome.endToEndId()));
    }
}
