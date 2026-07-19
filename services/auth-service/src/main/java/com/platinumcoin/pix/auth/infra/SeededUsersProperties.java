package com.platinumcoin.pix.auth.infra;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The demo user directory, bound from {@code auth.users[*]} in application.yml. There is no user
 * store in this local build (ADR-0007: seeded demo users, no AWS), so the "database" is config.
 * Passwords are stored as bcrypt hashes — never in the clear.
 */
@ConfigurationProperties(prefix = "auth")
public record SeededUsersProperties(List<SeededUser> users) {

    public record SeededUser(String username, String userId, String accountId, String passwordHash) {
    }
}
