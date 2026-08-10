package com.platinumcoin.pix.account.infra.config;

import com.platinumcoin.pix.account.domain.port.AccountRepository;
import com.platinumcoin.pix.account.domain.port.PixKeyRepository;
import com.platinumcoin.pix.account.domain.usecase.DeletePixKeyUseCase;
import com.platinumcoin.pix.account.domain.usecase.GetAccountUseCase;
import com.platinumcoin.pix.account.domain.usecase.GetMyAccountUseCase;
import com.platinumcoin.pix.account.domain.usecase.ListPixKeysUseCase;
import com.platinumcoin.pix.account.domain.usecase.RegisterPixKeyUseCase;
import com.platinumcoin.pix.account.domain.usecase.ResolvePixKeyUseCase;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for account-service's plain-Java domain (ADR-0010 + ADR-0011): {@code infra/}
 * instantiates every use case and wires it to its ports, so {@code domain/} classes carry no Spring
 * annotation at all — enforced by {@code AccountArchitectureTest}. Repositories are
 * {@code @Repository}-scanned in {@code infra/}; this class binds everything that has no framework
 * home of its own.
 *
 * <p>The {@link Clock} bean is what keeps {@code Instant.now()} out of the domain: a use case that
 * needs the current time takes it as a dependency, so a unit test can pin it (ADR-0011). A test that
 * needs a fixed clock overrides this single bean.
 */
@Configuration
public class AccountBeansConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    GetMyAccountUseCase getMyAccountUseCase(AccountRepository accounts) {
        return new GetMyAccountUseCase(accounts);
    }

    @Bean
    GetAccountUseCase getAccountUseCase(AccountRepository accounts) {
        return new GetAccountUseCase(accounts);
    }

    @Bean
    RegisterPixKeyUseCase registerPixKeyUseCase(PixKeyRepository keys, Clock clock) {
        return new RegisterPixKeyUseCase(keys, clock);
    }

    @Bean
    ListPixKeysUseCase listPixKeysUseCase(PixKeyRepository keys) {
        return new ListPixKeysUseCase(keys);
    }

    @Bean
    DeletePixKeyUseCase deletePixKeyUseCase(PixKeyRepository keys) {
        return new DeletePixKeyUseCase(keys);
    }

    @Bean
    ResolvePixKeyUseCase resolvePixKeyUseCase(PixKeyRepository keys) {
        return new ResolvePixKeyUseCase(keys);
    }
}
