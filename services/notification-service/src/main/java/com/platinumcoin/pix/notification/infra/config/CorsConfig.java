package com.platinumcoin.pix.notification.infra.config;

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
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Local-dev CORS so the single-file API explorer ({@code tools/api-explorer/index.html}) — opened from
 * disk, hence {@code Origin: null} — can open the SSE stream from the browser. Copies the pattern every
 * other service uses; see auth-service's {@code CorsConfig} for the full rationale.
 *
 * <p><b>Ordering is the whole point.</b> The {@link CorsFilter} registers <i>before</i> the common-lib
 * {@link JwtAuthFilter} (and before the SSE handshake filter at +9), so a credential-free pre-flight
 * {@code OPTIONS} is short-circuited instead of being rejected.
 *
 * <p><b>{@code Content-Type} is on the exposed headers here for a reason specific to streaming.</b> A
 * browser reading the stream with {@code fetch} — which is how the explorer does it, so it can send a
 * real {@code Authorization} header — cannot see the response's content type across origins unless the
 * server exposes it, and {@code text/event-stream} is exactly what tells the reader it got a stream
 * rather than an error page. Native {@code EventSource} is unaffected (it never reads headers), and the
 * correlation id stays exposed like everywhere else.
 *
 * <p>Permissive + credential-free for the local build; a deployed posture pins the origins (step-45
 * hardening).
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
        config.setExposedHeaders(List.of(CorrelationId.HEADER, HttpHeaders.CONTENT_TYPE));
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
