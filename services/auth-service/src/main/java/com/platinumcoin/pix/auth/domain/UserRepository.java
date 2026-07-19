package com.platinumcoin.pix.auth.domain;

import java.util.Optional;

/**
 * Outbound port: where the domain looks a user up by login name. Read-only — this build seeds
 * users in config and never creates or mutates them (auth-service owns no user lifecycle; a real
 * deployment backs this with an identity store). The in-memory adapter lives in {@code infra/};
 * the domain does not care which.
 */
public interface UserRepository {

    Optional<User> findByUsername(String username);
}
