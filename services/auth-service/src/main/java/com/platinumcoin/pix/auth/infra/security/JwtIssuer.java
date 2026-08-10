package com.platinumcoin.pix.auth.infra.security;

import com.platinumcoin.pix.auth.domain.model.IssuedToken;
import com.platinumcoin.pix.auth.domain.port.TokenIssuer;
import com.platinumcoin.pix.auth.infra.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

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

    private static final Logger log = LoggerFactory.getLogger(JwtIssuer.class);

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
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .subject(userId)
                .claim("accountId", accountId)
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();

        // The claim set, never the compact token: a token in a log is a usable credential, and
        // ADR-0012's "log the values" licence stops at secrets. jti is what ties this issuance to a
        // later request if token revocation/introspection ever lands.
        log.debug("Signed an HS256 access token, claims only (the token string is never logged) "
                        + "| jti={} sub={} accountId={} issuedAt={} expiresAt={} ttlSeconds={}",
                jti, userId, accountId, issuedAt, expiresAt, ttlSeconds);
        return new IssuedToken(token, ttlSeconds);
    }
}
