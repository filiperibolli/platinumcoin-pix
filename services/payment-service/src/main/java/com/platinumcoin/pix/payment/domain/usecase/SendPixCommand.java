package com.platinumcoin.pix.payment.domain.usecase;

/**
 * The intent of a send-Pix call, as it reaches the domain. The controller assembles it from the
 * validated request body, the {@code Idempotency-Key} header, and — crucially — the
 * {@code debtorAccountId} from the JWT, never from the payload (Domain Safety Rule #1). {@code amount}
 * is still the raw decimal string here; parsing to cents is the use case's job (ADR-0011: value
 * normalization lives in the use case, not the controller).
 *
 * <p>The {@code idempotencyKey} is the raw header value ({@code null}/blank if the client omitted it —
 * the use case, not the controller, decides that this is a {@code 400}). Together with
 * {@code debtorAccountId} it scopes the idempotency record; the {@code pixKey}/{@code amount}/
 * {@code description} triple is what the request-hash is computed over (Domain Safety Rule #2).
 */
public record SendPixCommand(
        String debtorAccountId,
        String pixKey,
        String amount,
        String description,
        String idempotencyKey) {
}
