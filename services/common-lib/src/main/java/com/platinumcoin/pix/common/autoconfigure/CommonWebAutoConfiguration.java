package com.platinumcoin.pix.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.error.GlobalExceptionHandler;
import com.platinumcoin.pix.common.security.AuthenticatedUserArgumentResolver;
import com.platinumcoin.pix.common.security.JwtAuthFilter;
import com.platinumcoin.pix.common.security.JwtAuthProperties;
import com.platinumcoin.pix.common.security.ServiceTokenIssuer;
import com.platinumcoin.pix.common.web.CorrelationIdFilter;
import com.platinumcoin.pix.common.web.CorrelationRestClientCustomizer;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Ships the shared web foundations to every service by the mere fact of depending on common-lib:
 * the correlation-id filter, the JWT auth filter + principal injection, the RFC 7807 error handler
 * and the outgoing-header propagation — zero per-service wiring.
 *
 * <p>Guarded so it only activates in a servlet web application that has the web types on its
 * classpath (common-lib declares them {@code optional}). Each bean is
 * {@link ConditionalOnMissingBean} so a service can override any piece if it ever needs to.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OncePerRequestFilter.class)
@EnableConfigurationProperties(JwtAuthProperties.class)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthFilter jwtAuthFilter(JwtAuthProperties properties, ObjectMapper objectMapper) {
        return new JwtAuthFilter(properties, objectMapper);
    }

    /**
     * Registers the {@link AuthenticatedUserArgumentResolver} so controllers can inject
     * {@link com.platinumcoin.pix.common.security.AuthenticatedUser} directly. Guarded on
     * {@link WebMvcConfigurer} — only present in an MVC service.
     */
    @Bean
    @ConditionalOnClass(WebMvcConfigurer.class)
    @ConditionalOnMissingBean(name = "authenticatedUserWebMvcConfigurer")
    public WebMvcConfigurer authenticatedUserWebMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new AuthenticatedUserArgumentResolver());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnMissingBean(name = "correlationRestClientCustomizer")
    public RestClientCustomizer correlationRestClientCustomizer() {
        return new CorrelationRestClientCustomizer();
    }

    /**
     * The scoped-service-token mint every outbound internal call goes through (step 68, ADR-0017).
     * Shipped from here rather than per service for the same reason the filter is: the platform has
     * <b>one</b> place that writes workload identity and one that reads it, so the two cannot drift.
     *
     * <p>Conditional on {@code jwt.service-name}: a token whose {@code iss} is empty is an anonymous
     * credential, so rather than mint one this bean simply does not exist — and a service that tries
     * to inject it without declaring its identity fails at startup, loudly, instead of at the far end
     * of a network hop with a 403.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "jwt", name = "service-name")
    public ServiceTokenIssuer serviceTokenIssuer(JwtAuthProperties properties, Clock clock) {
        return new ServiceTokenIssuer(properties.secret(), properties.serviceName(),
                properties.serviceTokenTtl().toSeconds(), clock);
    }

    /**
     * A UTC clock, so a service that has none of its own can still mint a token. Any service that
     * already defines a {@link Clock} bean (most do — ADR-0011 makes "now" an injected decision)
     * keeps its own.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
