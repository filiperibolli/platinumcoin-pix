package com.platinumcoin.pix.settlement.infra.config;

import com.platinumcoin.pix.common.security.JwtAuthFilter;
import com.platinumcoin.pix.common.web.CorrelationId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Local-dev CORS, arriving with this service's first HTTP endpoint (step 37) so the single-file API
 * explorer ({@code tools/api-explorer/index.html}) — opened from disk, hence {@code Origin: null} — can
 * call the inbound webhook from the browser. Copies the payment-service pattern verbatim; see
 * auth-service's {@code CorsConfig} for the full rationale.
 *
 * <p><b>Ordering is the whole point.</b> The {@link CorsFilter} registers <i>before</i> the common-lib
 * {@link JwtAuthFilter} (and after {@code CorrelationIdFilter}), so a credential-free pre-flight
 * {@code OPTIONS} is short-circuited instead of being rejected. The webhook route is JWT-exempt anyway,
 * but the ordering is kept identical to every other service rather than made a special case — the day
 * this service grows an authenticated endpoint, nothing has to be remembered.
 *
 * <p>Permissive + credential-free for the local build; a deployed posture pins the origins (step-45
 * hardening) — and in production this endpoint is not browser-reachable at all.
 */
@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** After {@code CorrelationIdFilter} (HIGHEST_PRECEDENCE), before {@link JwtAuthFilter} (+10). */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilterRegistration(
            @Value("${web.cors.allowed-origin-patterns:*}") List<String> allowedOriginPatterns) {
        log.info("Registered the local-dev CORS filter ahead of the JWT filter, so browser "
                        + "pre-flights are answered before authentication runs "
                        + "| order={} allowedOriginPatterns={} allowCredentials=false",
                ORDER, allowedOriginPatterns);
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOriginPatterns);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of(CorrelationId.HEADER));
        config.setAllowCredentials(false);
        config.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(ORDER);
        return registration;
    }
}
