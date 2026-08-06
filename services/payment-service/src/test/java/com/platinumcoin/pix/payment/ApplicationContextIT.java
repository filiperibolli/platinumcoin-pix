package com.platinumcoin.pix.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The Spring context loads: the DynamoDB client bean, the transaction repository adapter, the
 * {@code SendPixUseCase} and {@code EndToEndIdGenerator} wired in {@code PaymentBeansConfig}, the
 * controller and the inherited common-lib web foundations. Needs no LocalStack — the
 * {@code DynamoDbClient} connects lazily — so it stays a fast smoke test that catches broken wiring
 * (including a misconfigured {@code pix.ispb}) at build time.
 */
@SpringBootTest
class ApplicationContextIT {

    @Test
    void contextLoads() {
        // Wiring is asserted by the context starting successfully.
    }
}
