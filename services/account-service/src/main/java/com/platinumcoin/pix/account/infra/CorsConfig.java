package com.platinumcoin.pix.account.infra;

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
 * Local-dev CORS so the single-file API explorer ({@code tools/api-explorer/index.html}) — opened
 * from disk (Origin {@code null}) — can call account-service from the browser. Copies the
 * auth-service pattern verbatim (see services/auth-service/infra/CorsConfig for the full rationale).
 *
 * <p><b>Ordering is the whole point.</b> The {@link CorsFilter} registers <i>before</i> the
 * common-lib {@link JwtAuthFilter} (but after {@code CorrelationIdFilter}), so a credential-free
 * pre-flight {@code OPTIONS} is short-circuited instead of being rejected with {@code 401}.
 * Permissive + credential-free for the local build (token is a Bearer header, never a cookie); a
 * deployed posture pins the origins (step-45 hardening).
 */
@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** After {@code CorrelationIdFilter} (HIGHEST_PRECEDENCE), before {@link JwtAuthFilter} (+10). */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilterRegistration(
            @Value("${web.cors.allowed-origin-patterns:*}") List<String> allowedOriginPatterns) {
        // Startup breadcrumb: confirms the CORS filter is wired and its order relative to the auth
        // filter, so a container operator can see pre-flights are handled before auth runs.
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
