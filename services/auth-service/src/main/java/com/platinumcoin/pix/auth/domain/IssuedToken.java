package com.platinumcoin.pix.auth.domain;

/**
 * The result of a successful authentication: a signed access token plus its lifetime in seconds
 * (mirrors the OpenAPI {@code expiresIn}). A value object — no signing concern leaks in here.
 */
public record IssuedToken(String accessToken, long expiresInSeconds) {
}
