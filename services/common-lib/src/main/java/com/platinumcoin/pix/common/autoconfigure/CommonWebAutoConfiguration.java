package com.platinumcoin.pix.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.error.GlobalExceptionHandler;
import com.platinumcoin.pix.common.security.AuthenticatedUserArgumentResolver;
import com.platinumcoin.pix.common.security.JwtAuthFilter;
import com.platinumcoin.pix.common.security.JwtAuthProperties;
import com.platinumcoin.pix.common.web.CorrelationIdFilter;
import com.platinumcoin.pix.common.web.CorrelationRestClientCustomizer;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
}
