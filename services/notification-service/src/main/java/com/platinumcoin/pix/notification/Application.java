package com.platinumcoin.pix.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * notification-service (port 8087) — the platform's real-time push service, and its first
 * long-lived-connection service: it holds one SSE stream per connected customer and feeds them from
 * {@code notification-queue}.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
