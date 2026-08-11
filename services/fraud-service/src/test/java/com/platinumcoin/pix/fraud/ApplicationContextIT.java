package com.platinumcoin.pix.fraud;

import com.platinumcoin.pix.common.testsupport.RedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring context loads against a real Redis: the auto-configured {@code RedisConnectionFactory} and
 * {@code StringRedisTemplate} (from {@code spring.data.redis.*}), the {@code FraudBeansConfig} composition
 * root, the local-dev CORS filter and the inherited common-lib web/JWT foundations. Extends
 * {@link RedisTestBase} so a disposable {@code redis:7-alpine} container backs the connection, and proves
 * the wiring is live by round-tripping a key through the template — the connection fraud-service will use
 * for velocity counters in step 24 (DoD: "boots with a Redis connection").
 */
@SpringBootTest
class ApplicationContextIT extends RedisTestBase {

    @Autowired
    StringRedisTemplate redis;

    @Test
    void contextLoadsWithLiveRedisConnection() {
        redis.opsForValue().set("fraud:context-it:probe", "ready");

        assertThat(redis.opsForValue().get("fraud:context-it:probe")).isEqualTo("ready");
    }
}
