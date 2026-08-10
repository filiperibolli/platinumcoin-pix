package com.platinumcoin.pix.payment.api;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Mints valid HS256 tokens for the controller ITs. payment-service cannot call {@code /auth/login}
 * (it only validates tokens), so the test forges one signed with the same shared secret the
 * {@code JwtAuthFilter} verifies against ({@code application.yml} dev default). Same claim shape
 * auth-service issues: {@code sub} = userId, {@code accountId} claim, plus iat/exp.
 *
 * <p>Copy of the other services' {@code TestTokens} — deliberately duplicated rather than promoted to
 * common-lib's test-jar: it encodes a <i>dev secret</i>, and a shared helper that mints valid tokens
 * is exactly the kind of thing that should not become easy to reach from production code.
 */
final class TestTokens {

    /** Must match {@code jwt.secret} in src/main/resources/application.yml (dev default). */
    private static final String SECRET = "dev-only-hs256-secret-change-me-please-32b";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(UTF_8));

    private TestTokens() {
    }

    static String forUser(String userId, String accountId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("accountId", accountId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(KEY)
                .compact();
    }
}
