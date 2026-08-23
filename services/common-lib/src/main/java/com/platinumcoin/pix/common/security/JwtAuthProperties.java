package com.platinumcoin.pix.common.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validation-side JWT config, bound from {@code jwt.*} (shares the prefix auth-service uses to
 * <i>issue</i> — the same {@code JWT_SECRET} verifies what auth-service signed; ADR-0007's local
 * HS256 posture).
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
 *
 * <h2>Workload identity (step 68, ADR-0017)</h2>
 * {@code serviceName} is this service's identity, and it is <b>one</b> value because it is one fact: it
 * is the {@code aud} the filter demands on an inbound internal call, and the {@code iss} the issuer
 * stamps on an outbound one. Two properties for the same name would eventually disagree.
 *
 * <p>{@code internalRoutes} is the per-route scope map, declared here rather than in annotations on
 * controllers so a reviewer can read a service's entire internal attack surface in one screen of YAML.
 * It is a <b>list</b>, not a map: matching is first-match-wins, so the order has meaning and a list is
 * the only shape that states it. A route under {@code internalPathPatterns} that matches no entry is
 * <b>refused</b> — an internal port nobody scoped is a configuration mistake, and the safe reading of a
 * mistake on a money path is "no".
 *
 * @param secret               the shared HS256 secret; the same one auth-service signs with
 * @param publicPaths          ant patterns exempt from authentication entirely
 * @param serviceName          this service's workload identity — inbound {@code aud}, outbound {@code iss}
 * @param serviceTokenTtl      lifetime of a token this service mints for an outbound internal call
 * @param internalPathPatterns which paths are internal ports; defaults to {@code /internal/**}
 * @param internalRoutes       the scope required per internal route, first match wins
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtAuthProperties(
        String secret,
        List<String> publicPaths,
        String serviceName,
        Duration serviceTokenTtl,
        List<String> internalPathPatterns,
        List<InternalRoute> internalRoutes) {

    private static final List<String> DEFAULT_PUBLIC_PATHS =
            List.of("/v1/auth/login", "/actuator/**");
    private static final List<String> DEFAULT_INTERNAL_PATHS = List.of("/internal/**");
    private static final Duration DEFAULT_SERVICE_TOKEN_TTL = Duration.ofSeconds(60);

    public JwtAuthProperties {
        if (publicPaths == null || publicPaths.isEmpty()) {
            publicPaths = DEFAULT_PUBLIC_PATHS;
        }
        if (internalPathPatterns == null || internalPathPatterns.isEmpty()) {
            internalPathPatterns = DEFAULT_INTERNAL_PATHS;
        }
        if (serviceTokenTtl == null) {
            serviceTokenTtl = DEFAULT_SERVICE_TOKEN_TTL;
        }
        internalRoutes = internalRoutes == null ? List.of() : List.copyOf(internalRoutes);
    }

    /**
     * One internal route and the single scope it requires.
     *
     * @param method  HTTP method, or {@code null}/{@code *} for any
     * @param pattern ant-style path pattern, e.g. {@code /internal/ledger/accounts/**}
     * @param scope   the {@code scope} claim a caller must present — one of {@link InternalApi}'s
     */
    public record InternalRoute(String method, String pattern, String scope) {
    }
}
