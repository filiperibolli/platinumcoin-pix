package com.platinumcoin.pix.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Canonical-JSON hashing for the idempotency layer (ADR-0002, step 19). The request-hash stored under
 * an {@code Idempotency-Key} must be <b>stable across cosmetic variation</b> of the same logical
 * request — a retry that re-orders the JSON keys or re-indents the body is the <i>same</i> operation
 * and must replay, not {@code 409}. Only a change to a <i>value</i> (a different amount) may change the
 * hash.
 *
 * <p><b>Canonical form:</b> the JSON is parsed to a generic tree and re-serialized with object keys
 * sorted alphabetically (recursively, at every nesting level) and no insignificant whitespace. Two
 * inputs that differ only in key order or spacing therefore serialize to the identical string and hash
 * identically; two that differ in any value do not.
 *
 * <p>This is the platform's one place that turns JSON into a comparison key, so it lives in
 * {@code common-lib} (the shared adapter layer, exempt from the domain-purity ArchUnit rule) and uses
 * Jackson directly — a payment-service <i>use case</i> feeds it the request fields, keeping Jackson out
 * of {@code domain/}.
 */
public final class CanonicalJson {

    /**
     * Reader/writer pair configured once. {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}
     * applies to the {@code Map}s the JSON is read into, so nested objects sort too; the default
     * writer emits no insignificant whitespace, which is the "trimmed" half of the canonical form.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CanonicalJson() {
    }

    /**
     * Re-serialize an arbitrary JSON document into its canonical form: object keys sorted
     * (recursively), whitespace removed. Exposed so the canonicalization can be unit-tested directly
     * on raw JSON strings; the payment flow reaches it through {@link #hash(Map)}.
     *
     * @throws IllegalArgumentException the input is not well-formed JSON
     */
    public static String canonicalize(String json) {
        try {
            // Read into a generic Object (Map/List/scalar) rather than a JsonNode so that
            // ORDER_MAP_ENTRIES_BY_KEYS sorts every nested object on the way out.
            Object tree = MAPPER.readValue(json, Object.class);
            return MAPPER.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not well-formed JSON", e);
        }
    }

    /**
     * SHA-256 (hex, lower-case) of the canonical form of the given request fields. The map's own
     * iteration order is irrelevant — the canonical form sorts keys — so a caller may pass a plain
     * {@code Map.of(...)}. A {@code null} value is written as JSON {@code null}, so an absent optional
     * field and an explicit {@code null} hash the same.
     */
    public static String hash(Map<String, ?> fields) {
        try {
            return sha256Hex(MAPPER.writeValueAsString(fields));
        } catch (JsonProcessingException e) {
            // The fields come from validated request values (strings/nulls) — unreachable in practice.
            throw new IllegalArgumentException("request fields are not serializable", e);
        }
    }

    /** SHA-256 of the canonical form of a raw JSON string, hex-encoded. */
    public static String sha256HexOf(String json) {
        return sha256Hex(canonicalize(json));
    }

    private static String sha256Hex(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS on every JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
