package com.platinumcoin.pix.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * settlement-service entry point (port 8086) — the platform's SPI connector and its first
 * <b>queue-driven</b> service: it is not called by users, it long-polls {@code settlement-queue}, so its
 * scaling is driven by queue depth rather than by user traffic (ADR-0006).
 *
 * <p>Step 31 delivers the happy path: dedupe by {@code eventId}, claim the transaction as
 * {@code SENT_TO_SPI}, settle against BACEN, then move it to {@code SETTLED} together with a
 * {@code PixSettled} outbox event in one atomic write. Retries with query-before-retry and DLQ redrive
 * (step 32), reversal (step 33) and the reconciliation loop (steps 34–35) build on exactly these
 * transitions.
 *
 * <p>The service exposes <b>no business endpoint</b> — only Actuator, so compose can gate on its health.
 * Its one inbound adapter is the queue consumer.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
