package com.platinumcoin.pix.auth.infra;

import com.platinumcoin.pix.auth.domain.PasswordVerifier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * bcrypt adapter for {@link PasswordVerifier}. Delegates to a Spring {@link PasswordEncoder}
 * (BCryptPasswordEncoder) so the constant-time hash comparison and salt handling are not
 * hand-rolled. This is the only place the crypto library is touched.
 */
public class BCryptPasswordVerifier implements PasswordVerifier {

    private final PasswordEncoder encoder;

    public BCryptPasswordVerifier(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }
}
