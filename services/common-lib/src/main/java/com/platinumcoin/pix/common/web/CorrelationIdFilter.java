package com.platinumcoin.pix.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;

/**
 * Reads {@code X-Correlation-Id} from the incoming request (or generates a UUID if absent),
 * places it in the MDC so every downstream log line carries it, and echoes it on the response.
 *
 * <p>Runs at highest precedence so the id is available to every other filter, controller and
 * log statement in the request. The MDC entry is always removed in a {@code finally} block —
 * threads are pooled, so a leaked value would bleed into the next request on the same thread.
 *
 * <p><b>This filter logs nothing.</b> It used to emit one {@code INFO http.request …} line per call
 * so the id was visible somewhere; that is now the job of the <i>pattern</i> — common-lib's
 * {@code logback-spring.xml} puts {@code [cid=… tx=…]} on <b>every</b> record (ADR-0012), so the
 * correlation id no longer needs a line of its own and the healthcheck-shaped noise disappears with
 * it. What a request did is told by the business-stage INFO events in {@code domain/usecase/} plus
 * the adapter DEBUG lines in {@code infra/}; the auth outcome of every call is logged by
 * {@link com.platinumcoin.pix.common.security.JwtAuthFilter}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(CorrelationId.HEADER);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
