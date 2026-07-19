package com.platinumcoin.pix.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Login request body. Both fields are required; a blank one is a 400 {@code VALIDATION_ERROR}
 * (handled by common-lib), which is distinct from a well-formed-but-wrong credential (401).
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password) {
}
