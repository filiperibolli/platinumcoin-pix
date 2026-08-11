package com.platinumcoin.pix.fraud.api;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Mints valid HS256 tokens for the controller ITs. fraud-service cannot call {@code /auth/login} (it
 * only validates), so the test forges a token signed with the same shared secret the {@code
 * JwtAuthFilter} verifies against ({@code application.yml} dev default). The {@code /internal/**}
 * scoring endpoint is not on the public allow-list, so every request needs one.
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
