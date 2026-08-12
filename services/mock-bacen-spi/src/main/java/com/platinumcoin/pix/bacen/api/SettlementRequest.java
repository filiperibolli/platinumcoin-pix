package com.platinumcoin.pix.bacen.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * What a participant sends to settle one Pix over the rail — the contract settlement-service codes
 * against in step 31.
 *
 * <p><b>{@code endToEndId} is the idempotency key, and that is the only reason this endpoint is safe to
 * retry.</b> It is minted once by payment-service (Pix standard {@code E<ISPB><yyyyMMddHHmm><random>},
 * step 18) and stays fixed for the transaction's whole life, precisely so that a caller who timed out
 * can re-send without risking a second transfer.
 *
 * <p><b>Money is integer cents.</b> {@code amountCents} is a {@code long} and {@code @Positive}: the
 * stub never accepts a decimal string, so no {@code double} can be introduced at the platform's outer
 * edge. A zero or negative amount is not money and is refused as {@code 400 VALIDATION_ERROR} before
 * any settlement is considered.
 *
 * @param debtorIspb the paying participant (PlatinumCoin's own ISPB). Recorded in the logs for
 *                   realism; nothing routes on it here, since there is only one participant sending.
 */
public record SettlementRequest(
        @NotBlank(message = "endToEndId is required") String endToEndId,
        @NotBlank(message = "creditorKey is required") String creditorKey,
        @Positive(message = "amountCents must be strictly positive") long amountCents,
        String debtorIspb,
        String description) {
}
