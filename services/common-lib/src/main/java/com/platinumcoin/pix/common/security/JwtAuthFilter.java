package com.platinumcoin.pix.common.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.error.ProblemDetailFactory;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates {@code Authorization: Bearer <JWT>} on every request except the configured allow-list,
 * and turns a valid token into an {@link AuthenticatedUser} stashed on the request. This is the one
 * place the platform decides <b>who</b> is acting — so it <b>fails closed</b>: any missing, malformed,
 * badly-signed or expired token is a {@code 401 application/problem+json} with {@code code:UNAUTHORIZED}.
 *
 * <p>Runs just after {@link com.platinumcoin.pix.common.web.CorrelationIdFilter} (which has already
 * set the correlationId on the MDC and the response), so the 401 body carries it. Because a servlet
 * filter runs <i>before</i> the DispatcherServlet, the {@code @RestControllerAdvice} error handler
 * cannot see rejections here — the filter writes the RFC 7807 body itself, reusing
 * {@link ProblemDetailFactory} so the contract matches every other error.
 */
@Order(JwtAuthFilter.ORDER)
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Just after {@code CorrelationIdFilter} (highest precedence), before controllers. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ACCOUNT_ID_CLAIM = "accountId";

    private final SecretKey key;
    private final List<String> publicPaths;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthFilter(JwtAuthProperties properties, ObjectMapper objectMapper) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(UTF_8));
        this.publicPaths = properties.publicPaths();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (isPublic(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            reject(response, "missing_bearer_token");
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        AuthenticatedUser user;
        try {
            user = toPrincipal(parse(token));
        } catch (JwtException | IllegalArgumentException ex) {
            // Covers bad signature, expiry, malformed token, and blank/empty claims. One 401 for all —
            // never tell an attacker which check failed. The token itself is never logged.
            reject(response, ex.getClass().getSimpleName());
            return;
        }

        request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, user);
        filterChain.doFilter(request, response);
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (String pattern : publicPaths) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private AuthenticatedUser toPrincipal(Claims claims) {
        String userId = claims.getSubject();
        String accountId = claims.get(ACCOUNT_ID_CLAIM, String.class);
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(accountId)) {
            // A well-signed token that omits identity is as untrustworthy as a forged one.
            throw new IllegalArgumentException("token is missing subject or accountId claim");
        }
        return new AuthenticatedUser(userId, accountId);
    }

    private void reject(HttpServletResponse response, String reason) throws IOException {
        log.warn("auth.rejected reason={}", reason);
        ProblemDetail problem = ProblemDetailFactory.of(
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
