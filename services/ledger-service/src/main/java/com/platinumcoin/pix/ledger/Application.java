package com.platinumcoin.pix.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ledger-service entry point (port 8085) — the <b>only writer of {@code pix_ledger}</b> (ADR-0006,
 * docs/data-model.md §3). Step 13 delivers the read half:
 * {@code GET /internal/ledger/accounts/{accountId}/balance}, a strongly-consistent read of the
 * BALANCE item. The atomic double-entry posting arrives in step 14, the statement query in step 16.
 *
 * <p>The service is deliberately internal-only: it has no {@code /v1} surface, because no end user
 * talks to the ledger — payment-service does, on their behalf.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
