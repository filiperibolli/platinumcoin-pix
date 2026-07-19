package com.platinumcoin.pix.auth.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HS256 signing config, bound from {@code jwt.*}. {@code secret} is the shared symmetric key
 * (sourced from {@code JWT_SECRET} in real runs — see application.yml); {@code ttl} is the token
 * lifetime (15 min per ADR-0007). Constructor-bound record, so it is trivially instantiable in
 * unit tests too.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, Duration ttl) {
}
