package com.platinumcoin.pix.common.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Mints the short-lived, <b>scoped and addressed</b> HS256 token every service-to-service call carries
 * (ADR-0017). One token per call, for one target and one operation.
 *
 * <h2>Why a service token and not the caller's</h2>
 * Until step 68 this platform authenticated to its own internal ports by copying the end user's bearer
 * header onto the outbound call. That is the confused deputy in its natural habitat: payment-service is
 * trusted to post to the ledger, and it discharged that trust holding a credential belonging to someone
 * else. Every signature checked, every log line looked normal, and the consequence was concrete — a
 * token issued to <i>any</i> user was a valid credential on {@code POST /internal/ledger/postings}, the
 * platform's single money-moving operation, which names both accounts explicitly and derives nothing
 * from the token. See {@code InternalPortForbiddenIT} for that exploit, written as a test.
 *
 * <h2>The claim set, and why each claim is there</h2>
 * <ul>
 *   <li>{@code typ=service} — the claim the filter branches on. Without it, "a valid token" and "a
 *       token that may act as this system" are the same sentence, which is the whole defect.</li>
 *   <li>{@code iss} — the calling service. Answers <i>who is asking</i> in a log and, later, in the
 *       audit record of a posting.</li>
 *   <li>{@code aud} — the <b>target</b> service. Makes a token useless anywhere else, so a leak from
 *       fraud-service's logs does not become a ledger credential.</li>
 *   <li>{@code scope} — the one operation. A {@code ledger:post} token is refused for
 *       {@code ledger:read} and vice versa; this is what makes "escopo mínimo" checkable.</li>
 *   <li>{@code sub} = {@code iss}, {@code jti}, {@code iat}, {@code exp} — the ordinary skeleton. A short
 *       TTL keeps the blast radius of a leaked token tiny, and re-minting per call is one HMAC over a
 *       tiny payload: noise beside the DynamoDB and HTTP work in the same call.</li>
 * </ul>
 * There is deliberately <b>no {@code accountId}</b>. A service asserts no authority to spend anyone's
 * money, and a claim that looks like one would eventually be read as one.
 *
 * <h2>The signing key is still the shared secret</h2>
 * Same key, same filter, same verification path as every other token here — the identity is carried by
 * the <i>claims</i>, not by a per-service key. That is the sandbox-proportionate choice and ADR-0017
 * states it as such: the production posture is RS256 + JWKS (or mTLS), and the claim shape above is
 * chosen so that swapping it changes only the signature verification.
 *
 * <p>The compact token is <b>never logged</b> (ADR-0012): a token in a log is a usable credential, which
 * is where the "log the real values" licence stops. The claims are logged in full.
 */
public class ServiceTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenIssuer.class);

    private final SecretKey key;
    private final String serviceName;
    private final long ttlSeconds;
    private final Clock clock;

    public ServiceTokenIssuer(String secret, String serviceName, long ttlSeconds, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(UTF_8));
        this.serviceName = serviceName;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    /**
     * A freshly signed token addressed to {@code audience} and good for {@code scope} alone, valid for
     * the configured TTL from now.
     */
    public String issue(String audience, String scope) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .subject(serviceName)
                .claim(ServiceToken.TYP_CLAIM, ServiceToken.TYP_SERVICE)
                .issuer(serviceName)
                .audience().add(audience).and()
                .claim(ServiceToken.SCOPE_CLAIM, scope)
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();

        // Claims only — never the compact token (ADR-0012).
        log.debug("Signed a scoped HS256 service token for an internal call, claims only | jti={} "
                        + "iss={} aud={} scope={} issuedAt={} expiresAt={} ttlSeconds={}",
                jti, serviceName, audience, scope, issuedAt, expiresAt, ttlSeconds);
        return token;
    }

    /**
     * The form the outbound adapters use: attach a freshly minted token for {@code audience}/{@code scope},
     * plus the on-behalf-of <b>evidence</b> header naming the user whose request caused this call, when
     * there is one.
     *
     * <p>The two headers say different kinds of thing and it matters which is which. The bearer is
     * <b>authority</b> — signed, scoped, and the only thing the far end will act on. The on-behalf-of id
     * is <b>evidence</b> — unsigned, forgeable, and read by nothing but logs and the audit trail
     * ({@link OnBehalfOf}). A call with no HTTP request behind it (a queue consumer, a scheduled scan)
     * simply omits it: work no user asked for honestly has no user to name.
     */
    public void authorize(HttpHeaders headers, String audience, String scope) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + issue(audience, scope));
        currentUserId().ifPresent(userId -> headers.set(OnBehalfOf.HEADER, userId));
    }

    /** The authenticated user of the request this call is being made inside, if any. */
    private Optional<String> currentUserId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && attrs.getRequest().getAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE)
                        instanceof AuthenticatedUser user
                && StringUtils.hasText(user.userId())) {
            return Optional.of(user.userId());
        }
        return Optional.empty();
    }
}
