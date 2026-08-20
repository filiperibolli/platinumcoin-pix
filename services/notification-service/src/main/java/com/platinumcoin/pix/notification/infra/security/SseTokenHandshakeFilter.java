package com.platinumcoin.pix.notification.infra.security;

import com.platinumcoin.pix.common.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the SSE handshake's authentication nuance (step 38, task 4): it lets the stream be opened
 * with {@code ?access_token=<jwt>} by presenting that token to the rest of the chain as a normal
 * {@code Authorization: Bearer} header.
 *
 * <h2>Why the nuance exists at all</h2>
 * The browser's native {@code EventSource} — the whole reason SSE was chosen over WebSocket, since it
 * reconnects on its own over plain HTTP — <b>cannot set request headers</b>. There is no API for it.
 * So a stream that only accepts a header is a stream no {@code EventSource} can ever open, and the
 * step-05 allow-list hook was left precisely for this moment.
 *
 * <h2>What was NOT done, and why</h2>
 * The obvious reading of that hook is to put {@code /v1/notifications/stream} on
 * {@code jwt.public-paths} and validate the token inside this service. That would put a <b>second JWT
 * verification</b> in the platform, outside common-lib — and the one guarantee worth more than
 * convenience here is that every service validates a token the same way, so a fix or a hardening lands
 * once. Promoting the query parameter to a header instead keeps the route fully protected, leaves
 * {@link JwtAuthFilter} the only code that decides whether a token is good, and produces the identical
 * {@code 401 problem+json} every other endpoint produces. The allow-list stays untouched; ADR-0010's
 * dependency rule is unaffected (this is an inbound adapter concern, in {@code infra/security/}).
 *
 * <h2>The cost, stated plainly</h2>
 * A token in a URL is worse than a token in a header: it lands in access logs, in
 * {@code Referer} on any outbound link, and in browser history. That is accepted <i>here</i> because
 * this is a local sandbox, the token lives 15 minutes, and it buys the one client shape SSE exists for.
 * Two things keep the blast radius small: the promotion applies to <b>this single path</b> — no other
 * route in the platform can be authenticated by query string — and an explicit {@code Authorization}
 * header always wins, so curl and every service-to-service caller keep the better mechanism. The
 * production posture is a short-lived, single-use ticket minted for the stream (or a cookie), which is
 * the same shape with a credential that is worthless once used.
 */
@Order(SseTokenHandshakeFilter.ORDER)
public class SseTokenHandshakeFilter extends OncePerRequestFilter {

    /**
     * Immediately before {@link JwtAuthFilter} (and after the CORS filter at +5): the header has to
     * exist by the time the JWT filter reads it, and pre-flights must still be answered first.
     */
    public static final int ORDER = JwtAuthFilter.ORDER - 1;

    private static final Logger log = LoggerFactory.getLogger(SseTokenHandshakeFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final String streamPath;
    private final String parameterName;

    public SseTokenHandshakeFilter(String streamPath, String parameterName) {
        this.streamPath = streamPath;
        this.parameterName = parameterName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(promoteTokenIfNeeded(request), response);
    }

    private HttpServletRequest promoteTokenIfNeeded(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!streamPath.equals(path)) {
            return request;
        }
        // An explicit header always wins: it is the better mechanism, and honouring the query parameter
        // over it would let a crafted link override the credential a caller actually sent.
        if (StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            return request;
        }
        String token = request.getParameter(parameterName);
        if (!StringUtils.hasText(token)) {
            return request;
        }

        // The token itself is NEVER logged (ADR-0012 draws that line explicitly) — only the fact that
        // the handshake used the EventSource-compatible path, which is what makes a 401 here debuggable.
        log.debug("SSE handshake presented its token as a query parameter, promoting it to an "
                        + "Authorization header so the shared JWT filter validates it unchanged | "
                        + "path={} parameter={}", path, parameterName);
        return new BearerTokenRequest(request, token);
    }

    /**
     * Presents {@code Authorization: Bearer <token>} to everything downstream. Only the three header
     * accessors are overridden — the wrapper is otherwise the original request, so nothing else about
     * the call changes.
     */
    private static final class BearerTokenRequest extends HttpServletRequestWrapper {

        private final String headerValue;

        private BearerTokenRequest(HttpServletRequest request, String token) {
            super(request);
            this.headerValue = BEARER_PREFIX + token;
        }

        @Override
        public String getHeader(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                    ? headerValue
                    : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(headerValue))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            var names = new java.util.LinkedHashSet<String>();
            names.add(HttpHeaders.AUTHORIZATION);
            super.getHeaderNames().asIterator().forEachRemaining(names::add);
            return Collections.enumeration(names);
        }
    }
}
