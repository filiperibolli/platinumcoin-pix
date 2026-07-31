package com.platinumcoin.pix.account.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;

/**
 * Mints valid HS256 tokens for the controller ITs. account-service cannot call {@code /auth/login}
 * (it has no login — it only validates), so the test forges a token signed with the same shared
 * secret the {@code JwtAuthFilter} verifies against ({@code application.yml} dev default). Same claim
 * shape auth-service issues: {@code sub} = userId, {@code accountId} claim, plus iat/exp.
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
