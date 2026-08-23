package com.platinumcoin.pix.ledger.api;

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
 * Mints valid HS256 tokens for the controller ITs. ledger-service cannot call {@code /auth/login}
 * (it only validates tokens), so the test forges one signed with the same shared secret the
 * {@code JwtAuthFilter} verifies against ({@code application.yml} dev default). Same claim shape
 * auth-service issues: {@code sub} = userId, {@code accountId} claim, plus iat/exp.
 *
 * <p>Copy of account-service's {@code TestTokens} — deliberately duplicated rather than promoted to
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
