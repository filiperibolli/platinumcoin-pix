package com.platinumcoin.pix.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validation-side JWT config, bound from {@code jwt.*} (shares the prefix auth-service uses to
 * <i>issue</i> — the same {@code JWT_SECRET} verifies what auth-service signed; ADR-0007's local
 * HS256 posture). Only the pieces validation needs live here: the shared {@code secret} and the
 * {@code publicPaths} allow-list.
 *
 * <p>{@code publicPaths} is a seam: it defaults to login + the actuator surface, and a service can add
 * its own genuinely public routes by configuration rather than by editing the filter (settlement-service
 * adds {@code /v1/inbound/**}, the BACEN webhook, which holds no PlatinumCoin token). Ant-style patterns.
 *
 * <p><b>The SSE stream is deliberately NOT one of them</b> — worth recording, because this class used to
 * say it would be. Step 05 reserved the allow-list as the hook for the SSE handshake's awkwardness (a
 * browser's {@code EventSource} cannot set request headers, so it cannot send a bearer token); step 38
 * resolved it without spending the hook. notification-service's {@code SseTokenHandshakeFilter} runs
 * immediately before {@link JwtAuthFilter} and rewrites {@code ?access_token=} into an
 * {@code Authorization} header, so the route stays protected and <b>this filter remains the only code in
 * the platform that decides whether a token is good</b>. Making the path public would have bought the
 * same client compatibility at the price of a second JWT verification living outside common-lib.
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
