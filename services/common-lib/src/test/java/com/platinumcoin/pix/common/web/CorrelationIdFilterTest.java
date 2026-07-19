package com.platinumcoin.pix.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesAndEchoesCorrelationIdWhenHeaderAbsent() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var captured = new MdcCapturingChain();

        filter.doFilter(request, response, captured);

        String echoed = response.getHeader(CorrelationId.HEADER);
        assertThat(echoed).isNotBlank();
        // The value seen inside the chain (via MDC) is exactly what is echoed to the client.
        assertThat(captured.correlationIdDuringChain).isEqualTo(echoed);
    }

    @Test
    void preservesIncomingCorrelationId() throws Exception {
        String incoming = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, incoming);
        var response = new MockHttpServletResponse();
        var captured = new MdcCapturingChain();

        filter.doFilter(request, response, captured);

        assertThat(captured.correlationIdDuringChain).isEqualTo(incoming);
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(incoming);
    }

    @Test
    void cleansMdcAfterRequest() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    /** Captures the MDC correlationId as observed by downstream code inside the chain. */
    private static final class MdcCapturingChain implements FilterChain {
        private String correlationIdDuringChain;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response)
                throws IOException, ServletException {
            this.correlationIdDuringChain = MDC.get(CorrelationId.MDC_KEY);
        }
    }
}
