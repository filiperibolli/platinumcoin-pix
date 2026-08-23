package com.platinumcoin.pix.fraud.api;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.security.OnBehalfOf;
import com.platinumcoin.pix.common.testsupport.RedisTestBase;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>Step 69, scenario E on the fraud port: the refusal that would have poisoned the signals.</b>
 *
 * <h2>Why this service has the least obvious side effect of the three, and the nastiest</h2>
 * fraud-service's one internal route looks like a pure read — it answers a score. It is not: scoring
 * <b>records</b>, incrementing the account's velocity counter and amount sum in Redis and adding the
 * payee to its known-payees set. Those are the inputs to every <i>later</i> score.
 *
 * <p>So a refusal that ran the handler before rejecting the caller would not steal money and would not
 * show up in any balance. It would let anyone who can reach the port <b>move another account's fraud
 * signals</b> — inflating a victim's velocity until their legitimate sends are denied, or normalising an
 * attacker's payee so a later real send looks familiar. Slow, silent, and invisible to a conservation
 * audit, which is exactly why the assertion has to be made explicitly here rather than assumed from a
 * {@code 403}.
 *
 * <p>The counter is read straight out of Redis rather than inferred from a score, because a score is a
 * verdict and a verdict can stay the same while its inputs move.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LateralAccessIT extends RedisTestBase {

    private static final String NOON = "2026-07-07T12:00:00Z";
    private static final String COUNT_KEY_PREFIX = "fraud:vel:count:";
    private static final String PAYEES_KEY_PREFIX = "fraud:payees:";

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    private String victim;
    private String payeeKey;

    @BeforeEach
    void pickAVictim() {
        // A fresh account per test: the velocity counters live in a Redis shared by the whole class, and
        // "the counter did not move" is only meaningful against a counter nobody else is touching.
        victim = "acc-lateral-" + UUID.randomUUID();
        payeeKey = "payee-" + UUID.randomUUID() + "@platinumcoin.com";
    }

    static Stream<String> badCredentials() {
        return Stream.of("a user token", "a token addressed to another service", "a wrongly scoped token");
    }

    @ParameterizedTest(name = "POST /internal/fraud/score refuses {0} and records no signal")
    @MethodSource("badCredentials")
    void everyRefusalLeavesTheVictimsSignalsUntouched(String credential) throws Exception {
        assertThat(velocityCount(victim)).as("precondition: the victim has no history").isNull();

        mvc.perform(score(victim).header("Authorization", tokenFor(credential)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));

        assertThat(velocityCount(victim))
                .as("the velocity counter of an account nobody legitimately scored, after: %s", credential)
                .isNull();
        assertThat(redis.opsForSet().isMember(PAYEES_KEY_PREFIX + victim, payeeKey))
                .as("and the attacker's payee was not normalised into the victim's known set, after: %s",
                        credential)
                .isFalse();
    }

    /**
     * The control, and the reason the assertions above are not vacuous: the token payment-service really
     * mints does get through, and it <b>does</b> move the signals — by exactly one. A service that
     * recorded nothing at all would pass every refusal test in this class.
     */
    @Test
    void theCorrectServiceTokenIsAcceptedAndRecordsExactlyOneSignal() throws Exception {
        mvc.perform(score(victim).header("Authorization", "Bearer " + TestTokens.forService(
                        "payment-service", InternalApi.AUD_FRAUD, InternalApi.SCOPE_FRAUD_SCORE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").exists());

        assertThat(velocityCount(victim)).as("one accepted score, one recorded event").isEqualTo("1");
        assertThat(redis.opsForSet().isMember(PAYEES_KEY_PREFIX + victim, payeeKey)).isTrue();
    }

    /**
     * A forged on-behalf-of neither rescues a refusal nor redirects an accepted score onto the account it
     * names: the scored account comes from the request body the caller is authorised to send, and this
     * header is read by nothing (ADR-0017 decision 6).
     */
    @Test
    void aForgedOnBehalfOfHeaderChangesNoOutcome() throws Exception {
        mvc.perform(score(victim)
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001"))
                        .header(OnBehalfOf.HEADER, "u-someone-important"))
                .andExpect(status().isForbidden());
        assertThat(velocityCount(victim)).isNull();

        mvc.perform(score(victim)
                        .header("Authorization", "Bearer " + TestTokens.forService(
                                "payment-service", InternalApi.AUD_FRAUD, InternalApi.SCOPE_FRAUD_SCORE))
                        .header(OnBehalfOf.HEADER, "u-someone-important"))
                .andExpect(status().isOk());
        assertThat(velocityCount(victim))
                .as("the signal landed on the account the BODY named, not on the header's")
                .isEqualTo("1");
        assertThat(velocityCount("u-someone-important")).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static String tokenFor(String credential) {
        return "Bearer " + switch (credential) {
            case "a user token" -> TestTokens.forUser("u-alice", "acc-001");
            case "a token addressed to another service" -> TestTokens.forService(
                    "payment-service", InternalApi.AUD_LEDGER, InternalApi.SCOPE_FRAUD_SCORE);
            case "a wrongly scoped token" -> TestTokens.forService(
                    "payment-service", InternalApi.AUD_FRAUD, InternalApi.SCOPE_LEDGER_POST);
            default -> throw new IllegalArgumentException("unmapped credential " + credential);
        };
    }

    private MockHttpServletRequestBuilder score(String accountId) {
        return post("/internal/fraud/score")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId":"%s","pixKey":"%s","amountCents":1000,"timestamp":"%s"}"""
                        .formatted(accountId, payeeKey, NOON));
    }

    /** {@code null} when the account has no window open at all — the state a refusal must preserve. */
    private String velocityCount(String accountId) {
        return redis.opsForValue().get(COUNT_KEY_PREFIX + accountId);
    }
}
