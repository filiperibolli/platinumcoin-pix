package com.platinumcoin.pix.auth.infra;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the local-dev CORS policy lets the open-from-disk API explorer (Origin {@code null}) reach
 * auth-service — and, crucially, that a pre-flight to a <b>protected</b> path is answered by the CORS
 * filter instead of being rejected as unauthenticated by the common-lib {@code JwtAuthFilter}. That
 * only holds because {@link CorsConfig} orders the CORS filter ahead of the auth filter.
 *
 * <p>Uses MockMvc rather than a real HTTP client on purpose: the JDK's {@code HttpURLConnection}
 * silently strips restricted headers ({@code Origin}, {@code Access-Control-Request-*}), which would
 * make a live-socket CORS test lie.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsIT {

    @Autowired
    MockMvc mvc;

    @Test
    void preflightForProtectedEndpointIsAnsweredByCorsNotRejectedByAuth() throws Exception {
        mvc.perform(options("/v1/auth/me")
                        .header(HttpHeaders.ORIGIN, "null")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, is("null")));
    }

    @Test
    void actualCrossOriginRequestCarriesAllowOriginHeader() throws Exception {
        mvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, "null"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, is("null")));
    }
}
