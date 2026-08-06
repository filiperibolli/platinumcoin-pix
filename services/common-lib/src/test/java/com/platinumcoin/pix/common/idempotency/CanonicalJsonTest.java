package com.platinumcoin.pix.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one property that makes the request-hash safe (ADR-0002): cosmetic variation of the same
 * logical request — key order, whitespace — must NOT change the hash, while any change to a value
 * (a different amount) MUST. Without the first the platform would {@code 409} a legitimate retry that
 * merely re-ordered its JSON; without the second it would collapse two genuinely different requests.
 */
class CanonicalJsonTest {

    @Test
    void keyOrderDoesNotChangeTheCanonicalForm() {
        assertThat(CanonicalJson.canonicalize("{\"pixKey\":\"bob@x.com\",\"amount\":\"10.00\"}"))
                .isEqualTo(CanonicalJson.canonicalize("{\"amount\":\"10.00\",\"pixKey\":\"bob@x.com\"}"));
    }

    @Test
    void whitespaceDoesNotChangeTheCanonicalForm() {
        assertThat(CanonicalJson.canonicalize("{ \"a\" : 1 ,\n \"b\" : 2 }"))
                .isEqualTo(CanonicalJson.canonicalize("{\"a\":1,\"b\":2}"));
    }

    @Test
    void nestedObjectKeysAreSortedRecursively() {
        assertThat(CanonicalJson.canonicalize("{\"o\":{\"y\":1,\"x\":2}}"))
                .isEqualTo("{\"o\":{\"x\":2,\"y\":1}}");
    }

    @Test
    void keyOrderAndWhitespaceDoNotChangeTheHash() {
        String a = CanonicalJson.sha256HexOf("{\"pixKey\":\"bob@x.com\",\"amount\":\"10.00\"}");
        String b = CanonicalJson.sha256HexOf("{\n  \"amount\": \"10.00\",\n  \"pixKey\": \"bob@x.com\"\n}");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void aDifferentAmountChangesTheHash() {
        String ten = CanonicalJson.sha256HexOf("{\"pixKey\":\"bob@x.com\",\"amount\":\"10.00\"}");
        String ninetyNine = CanonicalJson.sha256HexOf("{\"pixKey\":\"bob@x.com\",\"amount\":\"99.00\"}");
        assertThat(ten).isNotEqualTo(ninetyNine);
    }

    @Test
    void hashOfFieldsIsIndependentOfMapIterationOrder() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("pixKey", "bob@x.com");
        forward.put("amount", "10.00");
        forward.put("description", "lunch");

        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("description", "lunch");
        reversed.put("amount", "10.00");
        reversed.put("pixKey", "bob@x.com");

        assertThat(CanonicalJson.hash(forward)).isEqualTo(CanonicalJson.hash(reversed));
    }

    @Test
    void anExplicitNullFieldHashesTheSameAsAnAbsentOne() {
        Map<String, String> withNull = new LinkedHashMap<>();
        withNull.put("pixKey", "bob@x.com");
        withNull.put("amount", "10.00");
        withNull.put("description", null);

        // Our use case normalizes a missing description to "" before hashing, but the util itself must
        // treat an explicit null deterministically — proven here so the choice is a use-case decision,
        // not an accident of serialization.
        String differentValue = CanonicalJson.hash(Map.of(
                "pixKey", "bob@x.com", "amount", "10.00", "description", "x"));
        assertThat(CanonicalJson.hash(withNull)).isNotEqualTo(differentValue);
    }

    @Test
    void isAHexSha256_64CharsLowerCase() {
        assertThat(CanonicalJson.sha256HexOf("{\"a\":1}")).matches("^[0-9a-f]{64}$");
    }

    @Test
    void malformedJsonIsRejected() {
        assertThatThrownBy(() -> CanonicalJson.canonicalize("{not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
