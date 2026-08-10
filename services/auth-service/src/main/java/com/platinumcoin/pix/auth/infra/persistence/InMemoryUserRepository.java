package com.platinumcoin.pix.auth.infra.persistence;

import com.platinumcoin.pix.auth.domain.model.User;
import com.platinumcoin.pix.auth.domain.port.UserRepository;
import com.platinumcoin.pix.auth.infra.config.SeededUsersProperties;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * In-memory adapter for {@link UserRepository}, built once from {@link SeededUsersProperties}.
 * Indexes the seeded users by username for O(1) lookup and maps the config row to the domain
 * {@link User} (dropping the login name — the domain identifies a user by {@code userId}).
 */
public class InMemoryUserRepository implements UserRepository {

    private final Map<String, User> byUsername;

    public InMemoryUserRepository(SeededUsersProperties properties) {
        this.byUsername = properties.users().stream()
                .collect(Collectors.toUnmodifiableMap(
                        SeededUsersProperties.SeededUser::username,
                        u -> new User(u.userId(), u.accountId(), u.passwordHash()),
                        (a, b) -> {
                            throw new IllegalStateException("Duplicate seeded username");
                        }));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }
}
