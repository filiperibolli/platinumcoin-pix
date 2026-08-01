package com.platinumcoin.pix.auth.infra;

import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.common.security.JwtAuthFilter;
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
 * from disk (Origin {@code null}) or from a local web server — can call this service from the browser.
 *
 * <p><b>Ordering is the whole point.</b> A CORS pre-flight {@code OPTIONS} carries no
 * {@code Authorization} header, so if the common-lib {@link JwtAuthFilter} (which runs on every
 * non-public path) saw it first it would answer {@code 401} and the real request would never be sent.
 * We register the {@link CorsFilter} <i>before</i> the auth filter (but after
 * {@link CorrelationIdFilter}, so pre-flights still get a correlationId) so it can short-circuit the
 * pre-flight before auth runs.
 *
 * <p>Deliberately permissive for the local build (default allowed-origin patterns = {@code *}) and
 * <b>credential-free</b>: the token travels as a {@code Bearer} header, never a cookie, so there is no
 * CSRF surface being widened. A deployed posture must pin the origins — tracked for the step-45
 * hardening pass. Override with {@code web.cors.allowed-origin-patterns} (comma-separated).
 */
@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** After {@link CorrelationIdFilter} (HIGHEST_PRECEDENCE), before {@link JwtAuthFilter} (+10). */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilterRegistration(
            @Value("${web.cors.allowed-origin-patterns:*}") List<String> allowedOriginPatterns) {
        // Startup breadcrumb: confirms the CORS filter is wired and where it sits relative to auth.
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
