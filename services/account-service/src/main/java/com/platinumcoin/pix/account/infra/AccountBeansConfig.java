package com.platinumcoin.pix.account.infra;

import com.platinumcoin.pix.account.domain.KeyResolutionService;
import com.platinumcoin.pix.account.domain.PixKeyRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for account-service's plain-Java domain services: {@code infra/} wires them to
 * their ports so the {@code domain/} classes stay free of any Spring annotation (ADR-0010, enforced
 * by {@code AccountArchitectureTest}). Repositories are {@code @Repository}-scanned in {@code infra/};
 * this only binds the domain services that have no framework home of their own.
 */
@Configuration
public class AccountBeansConfig {

    @Bean
    KeyResolutionService keyResolutionService(PixKeyRepository keys) {
        return new KeyResolutionService(keys);
    }
}
