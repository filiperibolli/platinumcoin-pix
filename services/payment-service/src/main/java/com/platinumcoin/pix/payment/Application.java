package com.platinumcoin.pix.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * payment-service entry point (port 8084) — the client-facing send-Pix API and the one endpoint an
 * end user actually reaches to move money. Step 18 delivers the <b>walking skeleton</b> of
 * {@code POST /v1/payments/pix}: JWT-authenticated, body validated per the OpenAPI contract, a
 * {@code txId} and Pix-standard {@code endToEndId} generated, the transaction persisted as
 * {@code RECEIVED} in {@code pix_transactions}, and a {@code 202 Accepted} returned with a
 * {@code Location} header.
 *
 * <p>Deliberately thin: no idempotency claim (step 19), no daily-limit reservation (step 20), no key
 * resolution or ledger debit (step 21). The point of the skeleton is to get the request <i>shape</i>
 * right — status codes, headers, ids, the debtor-from-JWT rule — before any behaviour thickens it.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
