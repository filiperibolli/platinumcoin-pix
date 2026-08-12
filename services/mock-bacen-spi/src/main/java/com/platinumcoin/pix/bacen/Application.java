package com.platinumcoin.pix.bacen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mock-bacen-spi entry point (port 9090) — the platform's stand-in for BACEN's <b>SPI</b> (the
 * instant-payment rail) and <b>DICT</b> (the key directory). It is the one component in the stack
 * whose job is to <i>misbehave on demand</i>: configurable latency (0–10s, the real SPI SLA), a
 * failure rate that answers {@code 503}, and a timeout rate that settles and then never answers.
 * Without a dependency we can break at will, Sprint 7's retries, DLQ and reconciliation could not be
 * tested at all — only hoped for.
 *
 * <p><b>Not a domain service.</b> No money lives here, no invariant is defended here, and nothing it
 * stores survives a restart (which is itself a useful drill: BACEN forgetting is a scenario, not a
 * bug). ADR-0010's scope note therefore grants it a thinner structure than the four real services —
 * {@code api/} inbound adapters over a small {@code spi/} core, with no ports, no use-case layer and
 * no ArchUnit test. That exemption is deliberate and bounded: everything else on the new-service
 * checklist (module, Dockerfile, compose entry, README, CORS, Postman, API explorer) still applies.
 *
 * <p><b>Trust boundary.</b> BACEN is an <i>external</i> party: it does not validate PlatinumCoin's
 * JWTs (a real participant authenticates to the SPI with mTLS and an ICP-Brasil certificate). The
 * inherited {@code JwtAuthFilter} is therefore neutralised via {@code jwt.public-paths: /**} rather
 * than removed — see {@code application.yml} for the full reasoning.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
