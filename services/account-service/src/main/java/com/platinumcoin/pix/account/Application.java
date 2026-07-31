package com.platinumcoin.pix.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * account-service entry point (port 8082). The first service that reads from DynamoDB via the AWS
 * SDK: exposes {@code GET /v1/accounts/me} (account from the JWT) and the internal lookup
 * {@code GET /internal/accounts/{accountId}}. Pix-key registration/resolution arrive in steps 10–11.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
