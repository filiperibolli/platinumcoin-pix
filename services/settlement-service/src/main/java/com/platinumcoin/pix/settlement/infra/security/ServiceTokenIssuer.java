package com.platinumcoin.pix.settlement.infra.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mints the short-lived HS256 token settlement-service presents to ledger-service's JWT-protected
 * {@code /internal/ledger/postings} (step 33). settlement runs off a queue, not an HTTP request, so it has
 * <b>no user token to forward</b> the way payment-service does — yet the money-moving endpoint is
 * deliberately behind the shared filter ({@code /internal/**} is absent from {@code jwt.public-paths}). So
 * it signs its own <b>service</b> token with the same shared secret the filter verifies against.
 *
 * <p><b>What this is and is not.</b> This is the sandbox stand-in for a real service credential; ADR-0013
 * records that the production posture (ambient IAM role / mTLS, a per-service credential) is the step-45
 * sweep. Until then, a token signed with the shared secret is exactly as strong as every other token in
 * the platform, and it keeps ledger-service's auth posture uniform — no public money endpoint, no
 * per-caller special case.
 *
 * <p>The claim set is the minimum the filter accepts (a non-blank {@code sub} and {@code accountId}): the
 * ledger posting names both accounts explicitly and never derives the debtor from the token, so the
 * {@code accountId} here is a system marker, not an authority to spend. A fresh token per call with a
 * short TTL keeps the blast radius of a leaked one tiny; the volume (one per finalization) makes re-minting
 * free. The compact token is <b>never logged</b> (ADR-0012): a token in a log is a usable credential.
 */
@Component
public class ServiceTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenIssuer.class);

    /** The service identity this token asserts — a marker, never a user, never an authority to spend. */
    private static final String SERVICE_PRINCIPAL = "settlement-service";

    private final SecretKey key;
    private final long ttlSeconds;
    private final Clock clock;

    public ServiceTokenIssuer(
            @Value("${jwt.secret}") String secret,
            @Value("${pix.service-auth.token-ttl-seconds:60}") long ttlSeconds,
            Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(UTF_8));
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    /** A freshly signed service token, valid for the configured TTL from now. */
    public String issue() {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .subject(SERVICE_PRINCIPAL)
                .claim("accountId", SERVICE_PRINCIPAL)
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();

        // Claims only — never the compact token (ADR-0012: the log-the-values licence stops at secrets).
        log.debug("Signed an HS256 service token for the ledger call, claims only | jti={} sub={} "
                + "accountId={} issuedAt={} expiresAt={} ttlSeconds={}",
                jti, SERVICE_PRINCIPAL, SERVICE_PRINCIPAL, issuedAt, expiresAt, ttlSeconds);
        return token;
    }
}
