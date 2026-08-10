package com.platinumcoin.pix.auth.domain.port;

/**
 * Outbound port hiding the hashing scheme (bcrypt) from the domain. Keeps the domain free of any
 * crypto library import while still letting {@link com.platinumcoin.pix.auth.domain.usecase.LoginUseCase} express "does this raw
 * password match the stored hash?".
 */
public interface PasswordVerifier {

    boolean matches(String rawPassword, String passwordHash);
}
