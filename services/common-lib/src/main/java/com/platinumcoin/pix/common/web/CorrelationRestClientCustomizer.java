package com.platinumcoin.pix.common.web;

import org.slf4j.MDC;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Propagates the current request's correlation id onto every outgoing {@link RestClient} call,
 * so the thread continues across service boundaries. The id is read from the MDC at request time
 * (via an interceptor), not at build time — the same client instance serves many requests.
 *
 * <p>Applied automatically to any {@code RestClient.Builder} a service builds, provided the
 * builder is obtained from the Spring-managed, auto-configured builder.
 */
public class CorrelationRestClientCustomizer implements RestClientCustomizer {

    @Override
    public void customize(RestClient.Builder builder) {
        builder.requestInterceptor((request, body, execution) -> {
            String correlationId = MDC.get(CorrelationId.MDC_KEY);
            if (StringUtils.hasText(correlationId) && !request.getHeaders().containsKey(CorrelationId.HEADER)) {
                request.getHeaders().add(CorrelationId.HEADER, correlationId);
            }
            return execution.execute(request, body);
        });
    }
}
