package com.platinumcoin.pix.auth.api;

import com.platinumcoin.pix.common.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code GET /v1/auth/me}: returns the caller identity carried by the validated
 * JWT. The {@link AuthenticatedUser} is injected by common-lib's argument resolver — the controller
 * never reads the {@code Authorization} header itself, and there is no way to name a different
 * account. Reaching this method at all already proves the token passed the shared auth filter.
 */
@RestController
@RequestMapping("/v1/auth")
public class MeController {

    private static final Logger log = LoggerFactory.getLogger(MeController.class);

    @GetMapping("/me")
    public MeResponse me(AuthenticatedUser user) {
        log.info("Returning the caller identity carried by the JWT | userId={} accountId={}",
                user.userId(), user.accountId());
        return MeResponse.from(user);
    }
}
