package com.platinumcoin.pix.notification.domain.usecase;

/**
 * A request to open a stream. Both fields come from the validated JWT and from nowhere else — there is
 * no wire field a caller could set to listen on somebody else's account. Domain Safety Rule #1 is
 * usually quoted about the debited account; the same reasoning governs reads, and it is stronger here
 * than a permission check would be, because the API shape simply cannot express the attack.
 */
public record OpenNotificationStreamCommand(String userId, String accountId) {

    public OpenNotificationStreamCommand {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required — it comes from the JWT claim");
        }
    }
}
