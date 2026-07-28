package com.platinumcoin.pix.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validation-side JWT config, bound from {@code jwt.*} (shares the prefix auth-service uses to
 * <i>issue</i> — the same {@code JWT_SECRET} verifies what auth-service signed; ADR-0007's local
 * HS256 posture). Only the pieces validation needs live here: the shared {@code secret} and the
 * {@code publicPaths} allow-list.
 *
 * <p>{@code publicPaths} is a seam: it defaults to login + the actuator surface, and step 38 adds
 * the SSE handshake path via configuration rather than by editing the filter. Ant-style patterns.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtAuthProperties(String secret, List<String> publicPaths) {

    private static final List<String> DEFAULT_PUBLIC_PATHS =
            List.of("/v1/auth/login", "/actuator/**");

    public JwtAuthProperties {
        if (publicPaths == null || publicPaths.isEmpty()) {
            publicPaths = DEFAULT_PUBLIC_PATHS;
        }
    }
}
