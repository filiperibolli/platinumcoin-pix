package com.platinumcoin.pix.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * auth-service entry point. Step 03 walking skeleton: boot, own port 8081, report health.
 * Login/JWT issuance arrives in step 04.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
