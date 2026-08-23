package com.platinumcoin.pix.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@code scripts/service-token.sh} mints a token this platform's own filter accepts.
 *
 * <p>The script exists so the runbook, the Postman collection and the API explorer can still reach an
 * {@code /internal/**} port now that a user's login is refused there (step 68). It reimplements the
 * issuer in {@code openssl}, and a reimplementation drifts — a padded base64, a {@code aud} written as
 * a string where jjwt writes an array, a signature over the wrong bytes. Each of those produces a
 * token that fails at the far end with an error message about authentication, sending whoever is
 * debugging it to look at the service instead of at the tool.
 *
 * <p>So this test runs the actual script and verifies its output the way the actual filter does. It is
 * the cheapest possible guard on a class of bug that is otherwise found by a confused human at a
 * terminal.
 *
 * <p>Skipped rather than failed where {@code bash}/{@code openssl} are unavailable: the script is a
 * developer convenience, not a build artifact, and a Windows CI box has no opinion about it.
 */
class ServiceTokenScriptParityTest {

    /** From {@code services/common-lib} up to the repo root. */
    private static final Path SCRIPT =
            Path.of("..", "..", "scripts", "service-token.sh").toAbsolutePath().normalize();

    /** The committed dev default the script falls back to and every service's application.yml uses. */
    private static final String DEV_SECRET = "dev-only-hs256-secret-change-me-please-32b";

    @Test
    void theScriptsTokenCarriesTheSameClaimsTheJavaIssuerWrites() throws Exception {
        assumeThat(Files.isExecutable(SCRIPT)).as("%s is executable", SCRIPT).isTrue();

        String token = run(SCRIPT.toString(), "ledger-service", "ledger:post", "local-cli");

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(DEV_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // Parsing at all is the signature assertion: parseSignedClaims throws on a bad HMAC.
        assertThat(claims.get(ServiceToken.TYP_CLAIM, String.class)).isEqualTo("service");
        assertThat(claims.getIssuer()).isEqualTo("local-cli");
        assertThat(claims.getAudience()).containsExactly("ledger-service");
        assertThat(claims.get(ServiceToken.SCOPE_CLAIM, String.class)).isEqualTo("ledger:post");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("script finished").isTrue();
        assertThat(process.exitValue()).as("script exit code (stderr: %s)",
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)).isZero();
        return out;
    }
}
