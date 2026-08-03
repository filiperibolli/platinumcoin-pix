package com.platinumcoin.pix.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.ledger.domain.Balance;
import org.junit.jupiter.api.Test;

/**
 * The money-formatting contract of the API edge, in isolation. This is the <b>only</b> place in
 * ledger-service where cents become a decimal string (docs/data-model.md: integer cents internally,
 * decimal strings on the wire), so it is the only place that can get it wrong.
 *
 * <p>Why this test exists at all, and why it uses no floating point: {@code 0.1} has no exact binary
 * representation, so {@code cents / 100.0} silently produces values like {@code 100.00000000000001}
 * and, worse, rounds differently depending on the magnitude of the number. {@link java.math.BigDecimal}
 * with a decimal shift is exact for every input, including the negative balance of {@code ACCOUNT#SEED}.
 */
class BalanceResponseTest {

    @Test
    void formatsSeededAliceBalanceAsDecimalString() {
        BalanceResponse response = BalanceResponse.from(new Balance("acc-001", 1_000_000L, 0L));

        assertThat(response.accountId()).isEqualTo("acc-001");
        assertThat(response.balance()).isEqualTo("10000.00");
        // Both representations of the same number: the string for humans, the cents for the internal
        // callers that do integer arithmetic on it (payment-service, step 21).
        assertThat(response.balanceCents()).isEqualTo(1_000_000L);
        assertThat(response.version()).isZero();
    }

    @Test
    void alwaysKeepsTwoDecimalPlaces() {
        assertThat(BalanceResponse.from(new Balance("acc-x", 0L, 0L)).balance()).isEqualTo("0.00");
        assertThat(BalanceResponse.from(new Balance("acc-x", 1L, 0L)).balance()).isEqualTo("0.01");
        assertThat(BalanceResponse.from(new Balance("acc-x", 50L, 0L)).balance()).isEqualTo("0.50");
        assertThat(BalanceResponse.from(new Balance("acc-x", 12_550L, 0L)).balance()).isEqualTo("125.50");
    }

    @Test
    void formatsTheNegativeBalanceOfASystemAccount() {
        // ACCOUNT#SEED is negative by construction — it is the double-entry counterpart of the money
        // supply (infra/localstack/init/05-seed-ledger.sh). The edge must render it, not reject it.
        assertThat(BalanceResponse.from(new Balance("SEED", -2_000_000L, 0L)).balance())
                .isEqualTo("-20000.00");
    }

    @Test
    void survivesAmountsThatWouldLoseCentsAsADouble() {
        // 92_233_720_368_547_758 cents is representable exactly as a long and NOT as a double.
        // The assertion is really about the absence of floating point anywhere in the path.
        assertThat(BalanceResponse.from(new Balance("acc-x", 92_233_720_368_547_758L, 0L)).balance())
                .isEqualTo("922337203685477.58");
    }
}
