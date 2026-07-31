package com.platinumcoin.pix.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The Spring context loads: the DynamoDB client bean, the repository adapter, both controllers and
 * the inherited common-lib web foundations all wire up. Deliberately does NOT need LocalStack — the
 * {@code DynamoDbClient} is built lazily (no connection at startup), so this stays a fast smoke test
 * that catches broken wiring at build time forever after.
 */
@SpringBootTest
class ApplicationContextIT {

    @Test
    void contextLoads() {
        // Wiring is asserted by the context starting successfully.
    }
}
