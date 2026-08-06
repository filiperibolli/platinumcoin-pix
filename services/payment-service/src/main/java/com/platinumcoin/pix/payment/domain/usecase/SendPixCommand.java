package com.platinumcoin.pix.payment.domain.usecase;

/**
 * The intent of a send-Pix call, as it reaches the domain. The controller assembles it from the
 * validated request body and — crucially — the {@code debtorAccountId} from the JWT, never from the
 * payload (Domain Safety Rule #1). {@code amount} is still the raw decimal string here; parsing to
 * cents is the use case's job (ADR-0011: value normalization lives in the use case, not the
 * controller).
 */
public record SendPixCommand(
        String debtorAccountId,
        String pixKey,
        String amount,
        String description) {
}
