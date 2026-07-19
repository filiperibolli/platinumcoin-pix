package com.platinumcoin.pix.auth.infra;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.auth.domain.IssuedToken;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * Unit test of the HS256 issuer: the token must verify under the shared secret, carry the exact
 * 15-minute lifetime the contract promises, and never repeat a {@code jti}.
 */
class JwtIssuerTest {

    // 32+ bytes: HS256 requires a key of at least 256 bits or jjwt refuses to sign.
    private static final String SECRET = "test-secret-test-secret-test-secret-01";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final JwtIssuer issuer = new JwtIssuer(new JwtProperties(SECRET, TTL));

    private Jws<Claims> parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }

    @Test
    void issuesTokenThatVerifiesWithTheSecretAndCarriesTheExpectedClaims() {
        IssuedToken token = issuer.issue("u-alice", "acc-001");

        assertThat(token.expiresInSeconds()).isEqualTo(900);

        Claims claims = parse(token.accessToken()).getPayload();
        assertThat(claims.getSubject()).isEqualTo("u-alice");
        assertThat(claims.get("accountId", String.class)).isEqualTo("acc-001");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void expiryIsExactlyFifteenMinutesAfterIssuedAt() {
        Claims claims = parse(issuer.issue("u-alice", "acc-001").accessToken()).getPayload();

        long lifetimeSeconds =
                (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(lifetimeSeconds).isEqualTo(900);
    }

    @Test
    void jtiIsUniqueAcrossCalls() {
        String first = parse(issuer.issue("u-alice", "acc-001").accessToken()).getPayload().getId();
        String second = parse(issuer.issue("u-alice", "acc-001").accessToken()).getPayload().getId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tamperedTokenFailsSignatureVerification() {
        String token = issuer.issue("u-alice", "acc-001").accessToken();
        // Forge a token by signing with a DIFFERENT key: its signature must not verify under our
        // secret. This is the real attack HS256 defends against.
        SecretKey attackerKey =
                Keys.hmacShaKeyFor("attacker-secret-attacker-secret-01".getBytes(UTF_8));
        String forged = Jwts.builder()
                .subject("u-alice")
                .claim("accountId", "acc-999")
                .signWith(attackerKey)
                .compact();
        assertThat(forged).isNotEqualTo(token);

        org.junit.jupiter.api.Assertions.assertThrows(
                io.jsonwebtoken.JwtException.class, () -> parse(forged));
    }
}
