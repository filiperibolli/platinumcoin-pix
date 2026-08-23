package com.platinumcoin.pix.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

/**
 * The claim set of a service token, its TTL, and the one thing that must never appear in a log.
 *
 * <p>The last assertion is the unusual one and it is deliberate. CLAUDE.md forbids asserting on log
 * <i>text</i> — logs are prose for humans, and pinning their wording makes every rewording a test
 * failure. This class asserts the opposite kind of fact: not that a particular sentence was written,
 * but that a particular <b>value</b> was not. A compact JWT in a log file is a usable credential for
 * whoever can read the file, so "the token never reaches a log" is a security property of the issuer,
 * not a phrasing preference — and a property with no test that tries to break it is a comment.
 */
class ServiceTokenIssuerTest {

    private static final String SECRET = "test-only-hs256-secret-change-me-please-32b";
    private static final Instant NOW = Instant.parse("2026-08-23T10:15:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger issuerLogger;

    @BeforeEach
    void captureLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        issuerLogger = context.getLogger(ServiceTokenIssuer.class);
        issuerLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        issuerLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        issuerLogger.detachAppender(appender);
    }

    @Test
    void mintsTheFullWorkloadIdentityClaimSet() {
        String token = issuer("payment-service")
                .issue(InternalApi.AUD_LEDGER, InternalApi.SCOPE_LEDGER_POST);

        Claims claims = parse(token);
        assertThat(claims.get(ServiceToken.TYP_CLAIM, String.class)).isEqualTo("service");
        assertThat(claims.getIssuer()).isEqualTo("payment-service");
        assertThat(claims.getSubject()).isEqualTo("payment-service");
        assertThat(claims.getAudience()).containsExactly("ledger-service");
        assertThat(claims.get(ServiceToken.SCOPE_CLAIM, String.class)).isEqualTo("ledger:post");
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    void carriesNoAccountIdBecauseAServiceHasNoMoneyToSpend() {
        Claims claims = parse(issuer("payment-service")
                .issue(InternalApi.AUD_LEDGER, InternalApi.SCOPE_LEDGER_POST));

        // A claim that looks like authority over an account would eventually be read as one — which
        // is exactly the confusion (a service acting as a person) this whole step removes.
        assertThat(claims.get("accountId", String.class)).isNull();
    }

    @Test
    void expiresExactlyTheConfiguredTtlAfterTheInjectedClock() {
        Claims claims = parse(issuer("payment-service")
                .issue(InternalApi.AUD_LEDGER, InternalApi.SCOPE_LEDGER_READ));

        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void everyCallMintsAFreshToken() {
        var issuer = issuer("payment-service");

        // Same clock, same claims — but a distinct jti, so two calls are never the same credential.
        assertThat(parse(issuer.issue(InternalApi.AUD_LEDGER, InternalApi.SCOPE_LEDGER_POST)).getId())
                .isNotEqualTo(parse(issuer.issue(InternalApi.AUD_LEDGER,
                        InternalApi.SCOPE_LEDGER_POST)).getId());
    }

    @Test
    void theCompactTokenNeverReachesALog() {
        String token = issuer("payment-service")
                .issue(InternalApi.AUD_LEDGER, InternalApi.SCOPE_LEDGER_POST);

        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent event : appender.list) {
            String rendered = event.getFormattedMessage();
            assertThat(rendered).doesNotContain(token);
            // Nor any segment of it: a JWT is three dot-separated parts, and the signature alone
            // would be enough for an attacker holding the header and payload to confirm a forgery.
            for (String part : token.split("\\.")) {
                assertThat(rendered).doesNotContain(part);
            }
        }
    }

    @Test
    void theClaimsDoReachTheLogBecauseThatIsWhatMakesARefusalDebuggable() {
        issuer("payment-service").issue(InternalApi.AUD_LEDGER, InternalApi.SCOPE_LEDGER_POST);

        // The complement of the test above, and the reason it is not simply "log nothing": ADR-0012's
        // licence to print the real values stops at secrets, and stops nowhere earlier.
        assertThat(appender.list)
                .anySatisfy(event -> assertThat(Arrays.toString(event.getArgumentArray()))
                        .contains("payment-service")
                        .contains("ledger-service")
                        .contains("ledger:post"));
    }

    @Test
    void authorizeAttachesTheBearerAndOmitsOnBehalfOfWhenNoUserIsBehindTheCall() {
        var headers = new HttpHeaders();

        // A queue consumer or a scheduled scan: real work, no human to name. The header is absent
        // rather than blank — work nobody asked for honestly has no user.
        issuer("settlement-service").authorize(headers, InternalApi.AUD_LEDGER,
                InternalApi.SCOPE_LEDGER_POST);

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).startsWith("Bearer ");
        assertThat(headers.containsKey(OnBehalfOf.HEADER)).isFalse();
    }

    private ServiceTokenIssuer issuer(String serviceName) {
        return new ServiceTokenIssuer(SECRET, serviceName, 60L, clock);
    }

    /**
     * Verifies against the same fixed instant the issuer minted at. Without it the parser would use
     * the wall clock and reject a token whose 60s TTL expired the moment this test file was written —
     * which would be a test of {@link Clock}, not of the issuer.
     */
    private static Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .clock(() -> Date.from(NOW))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
