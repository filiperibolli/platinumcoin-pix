package com.platinumcoin.pix.auth.infra.config;

import com.platinumcoin.pix.auth.domain.port.PasswordVerifier;
import com.platinumcoin.pix.auth.domain.port.TokenIssuer;
import com.platinumcoin.pix.auth.domain.port.UserRepository;
import com.platinumcoin.pix.auth.domain.usecase.LoginUseCase;
import com.platinumcoin.pix.auth.infra.persistence.InMemoryUserRepository;
import com.platinumcoin.pix.auth.infra.security.BCryptPasswordVerifier;
import com.platinumcoin.pix.auth.infra.security.JwtIssuer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Composition root for auth-service: the {@code infra/} layer wires the plain-Java domain to its
 * adapters, so {@link LoginUseCase} stays free of any Spring annotation (ADR-0010 + ADR-0011).
 * Binds the two config records here rather than scanning, keeping the property surface explicit.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, SeededUsersProperties.class})
public class AuthBeansConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    PasswordVerifier passwordVerifier(PasswordEncoder encoder) {
        return new BCryptPasswordVerifier(encoder);
    }

    @Bean
    TokenIssuer tokenIssuer(JwtProperties jwtProperties) {
        return new JwtIssuer(jwtProperties);
    }

    @Bean
    UserRepository userRepository(SeededUsersProperties seededUsers) {
        return new InMemoryUserRepository(seededUsers);
    }

    @Bean
    LoginUseCase loginUseCase(UserRepository users, PasswordVerifier passwordVerifier,
            TokenIssuer tokenIssuer) {
        return new LoginUseCase(users, passwordVerifier, tokenIssuer);
    }
}
