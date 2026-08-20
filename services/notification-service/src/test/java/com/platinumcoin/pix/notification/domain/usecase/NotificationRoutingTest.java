package com.platinumcoin.pix.notification.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.notification.domain.service.NotificationRouting;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one policy decision this service makes: <b>whose stream does this event belong on?</b> Getting it
 * wrong is not a cosmetic bug — it would push one customer's payment activity (amount, counterpart, id)
 * onto another customer's screen, so it is pinned here in plain Java before any transport exists.
 */
class NotificationRoutingTest {

    @Test
    void pixReceivedGoesToThePayee() {
        // An inbound Pix: the money landed in the creditor's account, and the creditor is the only
        // PlatinumCoin customer involved — the payer banks elsewhere.
        var command = command("PixReceived", null, "acc-002");

        assertThat(NotificationRouting.affectedAccountId(command)).isEqualTo("acc-002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PixSettled", "PixReversed"})
    void outboundOutcomesGoToThePayer(String eventType) {
        // The payer is who asked for this payment and who saw the 202; they are owed the final answer.
        var command = command(eventType, "acc-001", null);

        assertThat(NotificationRouting.affectedAccountId(command)).isEqualTo("acc-001");
    }

    @Test
    void anInternalSettlementStillGoesToThePayer() {
        // An INTERNAL send carries BOTH accounts (payment-service's PixSettled, step 21). The payer is
        // still the addressee: this event announces "your send completed", and it is not a second copy
        // of it that the payee needs but an arrival event of their own — which payment-service does not
        // emit today (known gap, README; the event carries no payer display name to build one from).
        var command = command("PixSettled", "acc-001", "acc-002");

        assertThat(NotificationRouting.affectedAccountId(command)).isEqualTo("acc-001");
    }

    @Test
    void anEventTypeThisServiceDoesNotKnowIsUnroutable() {
        // The subscription filter should make this impossible (step 36). If one slips through, "no
        // addressee" must be an explicit answer the caller can act on — never a guess at an account.
        var command = command("PixDebited", "acc-001", "acc-002");

        assertThat(NotificationRouting.affectedAccountId(command)).isNull();
    }

    @Test
    void aKnownEventTypeMissingItsAddresseeIsUnroutable() {
        var command = command("PixReceived", "acc-001", null);

        assertThat(NotificationRouting.affectedAccountId(command)).isNull();
    }

    private static DeliverNotificationCommand command(
            String eventType, String debtorAccountId, String creditorAccountId) {
        return new DeliverNotificationCommand("evt-1", eventType, "cid-1", "tx-1",
                debtorAccountId, creditorAccountId, 12_345L, "bob@otherbank.com", "Carol", "99999999",
                null, null, null, Instant.parse("2026-08-20T10:15:00Z"));
    }
}
