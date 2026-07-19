package com.platinumcoin.pix.auth.domain;

/**
 * A seeded demo user. {@code userId} becomes the JWT {@code sub}; {@code accountId} becomes the
 * {@code accountId} claim — the single source later services trust for the debited account
 * (Domain Safety Rule #1). {@code passwordHash} is a bcrypt hash; the raw password never lives here.
 */
public record User(String userId, String accountId, String passwordHash) {
}
