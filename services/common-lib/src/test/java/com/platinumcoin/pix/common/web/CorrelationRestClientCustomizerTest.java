package com.platinumcoin.pix.common.web;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CorrelationRestClientCustomizerTest {

    private final CorrelationRestClientCustomizer customizer = new CorrelationRestClientCustomizer();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesCorrelationIdFromMdcOntoOutgoingRequest() {
        MDC.put(CorrelationId.MDC_KEY, "corr-123");

        RestClient.Builder builder = RestClient.builder();
        customizer.customize(builder);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://downstream/ping"))
                .andExpect(header(CorrelationId.HEADER, "corr-123"))
                .andRespond(withSuccess());

        builder.build().get().uri("http://downstream/ping").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void doesNotAddHeaderWhenNoCorrelationIdInMdc() {
        RestClient.Builder builder = RestClient.builder();
        customizer.customize(builder);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://downstream/ping"))
                .andExpect(headerDoesNotExist(CorrelationId.HEADER))
                .andRespond(withSuccess());

        builder.build().get().uri("http://downstream/ping").retrieve().toBodilessEntity();

        server.verify();
    }
}
