package com.platinumcoin.pix.auth.infra;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.platinumcoin.pix.auth.domain.IssuedToken;
import com.platinumcoin.pix.auth.domain.TokenIssuer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * HS256 adapter for {@link TokenIssuer}. Mints exactly the claim set the contract promises —
 * {@code sub}, {@code accountId}, {@code jti}, {@code iat}, {@code exp} — and nothing else.
 *
 * <p>{@code iat} and {@code exp} are both derived from a single captured instant so their
 * difference is exactly the configured TTL (JWT timestamps are whole seconds; deriving both from
 * one instant avoids an off-by-one from two separate {@code now()} reads). The symmetric key is
 * built once from the shared secret; HS256 mandates ≥ 256 bits, so the secret must be ≥ 32 bytes.
 */
public class JwtIssuer implements TokenIssuer {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtIssuer(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(UTF_8));
        this.ttlSeconds = properties.ttl().toSeconds();
    }

    @Override
    public IssuedToken issue(String userId, String accountId) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);

        String token = Jwts.builder()
                .subject(userId)
                .claim("accountId", accountId)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();

        return new IssuedToken(token, ttlSeconds);
    }
}
