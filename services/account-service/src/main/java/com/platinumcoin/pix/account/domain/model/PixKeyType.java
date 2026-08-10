package com.platinumcoin.pix.account.domain.model;

import java.util.regex.Pattern;

/**
 * The four Pix key kinds (docs/data-model.md §2). Each constant owns two things the rest of the
 * domain needs, and nothing framework-shaped — so it obeys the ADR-0010 dependency rule:
 *
 * <ul>
 *   <li><b>normalization</b> ({@link #normalize(String)}) — the canonical form that becomes the
 *       global-uniqueness key. EMAIL is case-insensitive (trim + lowercase) so {@code Alice@x.com}
 *       and {@code alice@x.com} cannot both be registered; CPF/PHONE are only trimmed.</li>
 *   <li><b>format validation</b> ({@link #matches(String)}) — a <i>format-only</i> check (no CPF
 *       check-digit): real DICT validation is BACEN's job, not this service's. Well-formed JSON with
 *       a value that fails this maps to {@code 422 INVALID_PIX_KEY} at the api edge.</li>
 * </ul>
 *
 * <p>{@link #EVP} is <b>server-generated</b>: its value is a random UUID minted on registration and
 * the client's {@code keyValue} is ignored entirely ({@link #isServerGenerated()}).
 */
public enum PixKeyType {

    /** Brazilian tax id — format-only here: exactly 11 digits (no check-digit validation). */
    CPF(Pattern.compile("^\\d{11}$"), false),

    /** Pragmatic e-mail shape; normalized to lowercase so uniqueness is case-insensitive. */
    EMAIL(Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"), false),

    /** E.164 phone (e.g. {@code +5511999999999}): a leading {@code +} then 8–15 digits. */
    PHONE(Pattern.compile("^\\+\\d{8,15}$"), false),

    /** Random-key: a UUID the server generates; the client cannot choose its value. */
    EVP(Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"), true);

    private final Pattern format;
    private final boolean serverGenerated;

    PixKeyType(Pattern format, boolean serverGenerated) {
        this.format = format;
        this.serverGenerated = serverGenerated;
    }

    /** {@code true} for {@link #EVP}: the value is minted by the server, never taken from the client. */
    public boolean isServerGenerated() {
        return serverGenerated;
    }

    /**
     * Canonical form used as the global-uniqueness key. {@code null} is treated as blank so callers
     * never NPE on a missing field; the empty result then fails {@link #matches(String)}.
     */
    public String normalize(String rawValue) {
        String trimmed = rawValue == null ? "" : rawValue.trim();
        return this == EMAIL ? trimmed.toLowerCase() : trimmed;
    }

    /** Format-only validity of an already-normalized value. */
    public boolean matches(String normalizedValue) {
        return normalizedValue != null && format.matcher(normalizedValue).matches();
    }
}
