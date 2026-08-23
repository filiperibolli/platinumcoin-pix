package com.platinumcoin.pix.fraud.api;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.security.ServiceToken;
import com.platinumcoin.pix.common.security.ServiceTokenIssuer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
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
                .claim(ServiceToken.TYP_CLAIM, ServiceToken.TYP_USER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(KEY)
                .compact();
    }

    /**
     * A <b>service</b> token (step 68, ADR-0017): {@code typ=service} plus the {@code iss}/{@code aud}/
     * {@code scope} the shared filter checks on every {@code /internal/**} call. Built through the real
     * {@link ServiceTokenIssuer} rather than a hand-rolled builder, so a test can never accidentally
     * pass by minting a claim set production would not — the point of the negative matrix is that the
     * only tokens that get through are ones the platform actually issues.
     *
     * @param issuer   the calling service, e.g. {@code payment-service}
     * @param audience the target service the token is addressed to
     * @param scope    the single operation it is good for ({@link InternalApi})
     */
    static String forService(String issuer, String audience, String scope) {
        return new ServiceTokenIssuer(SECRET, issuer, 60L, Clock.systemUTC()).issue(audience, scope);
    }
}
