package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.common.security.AuthenticatedUser;
import com.platinumcoin.pix.payment.domain.Transaction;
import com.platinumcoin.pix.payment.domain.usecase.SendPixCommand;
import com.platinumcoin.pix.payment.domain.usecase.SendPixUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code POST /v1/payments/pix}. Per ADR-0011 it does exactly three things: bind +
 * bean-validate the wire shape ({@link SendPixRequest}), call one use case, and map the result to
 * {@code 202 Accepted} with a {@code Location} header. No policy lives here — id generation, amount
 * parsing and the clock are all inside {@link SendPixUseCase}.
 *
 * <p><b>The debtor comes from the token.</b> {@link AuthenticatedUser} (injected by common-lib from
 * the validated JWT) supplies {@code accountId}; the request body has no source-account field, so the
 * debited account is inexpressible from the payload (Domain Safety Rule #1).
 *
 * <p><b>Idempotency-Key.</b> The header is REQUIRED by the contract, but this skeleton neither reads
 * nor enforces it — the conditional claim + response replay + 409-on-hash-mismatch is step 19. It is
 * accepted and ignored for now so the endpoint's shape is stable while the behaviour is added.
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
            @Valid @RequestBody SendPixRequest request, AuthenticatedUser user) {
        Transaction transaction = sendPix.execute(new SendPixCommand(
                user.accountId(), request.pixKey(), request.amount(), request.description()));
        return ResponseEntity
                .accepted()
                .location(URI.create("/v1/payments/" + transaction.txId()))
                .body(PaymentAcceptedResponse.from(transaction));
    }
}
