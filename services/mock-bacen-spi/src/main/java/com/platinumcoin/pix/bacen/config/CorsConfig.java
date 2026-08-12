package com.platinumcoin.pix.bacen.config;

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
 * Local-dev CORS so the single-file API explorer ({@code tools/api-explorer/index.html}) — opened from
 * disk, hence {@code Origin: null} — can arm and disarm the failure injection from the browser. Copies
 * the auth-service pattern verbatim (see that class for the full rationale).
 *
 * <p>Registered <i>before</i> the inherited {@link JwtAuthFilter} (and after {@code CorrelationIdFilter})
 * so a credential-free pre-flight {@code OPTIONS} is short-circuited rather than authenticated. Here the
 * ordering is belt-and-braces — every path of this stub is public anyway ({@code jwt.public-paths: /**},
 * because BACEN is an external party that knows nothing of PlatinumCoin's tokens) — but the block is kept
 * identical to the other services so the pattern reads the same everywhere and survives a future in which
 * the stub does gate something.
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
