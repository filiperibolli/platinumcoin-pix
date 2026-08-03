package com.platinumcoin.pix.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The Spring context loads: the DynamoDB client bean, the repository adapter, the use case wired in
 * {@code LedgerBeansConfig}, the controller and the inherited common-lib web foundations. Needs no
 * LocalStack — the {@code DynamoDbClient} connects lazily — so it stays a fast smoke test that
 * catches broken wiring at build time.
 */
@SpringBootTest
class ApplicationContextIT {

    @Test
    void contextLoads() {
        // Wiring is asserted by the context starting successfully.
    }
}
