package com.platinumcoin.pix.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>Shared request log.</b> Because this filter wraps the whole chain in <i>every</i> service
 * (it ships from common-lib), it also emits one {@code INFO http.request} line per call with method,
 * path, status and duration — so every inbound call is observable at INFO in the container logs,
 * correlated by id, with zero per-service wiring. Actuator probes are skipped to keep the log
 * high-signal (healthchecks fire every few seconds). This is the platform-wide "one line per call"
 * that money-moving flows build their business-stage INFO logs on top of.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(CorrelationId.HEADER);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Log before clearing the MDC so the line carries the correlationId. Skip actuator so
            // healthcheck probes don't drown the real calls.
            if (!request.getRequestURI().startsWith("/actuator")) {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("http.request method={} path={} status={} durationMs={}",
                        request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            }
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
