package com.platinumcoin.pix.auth.api;

import com.platinumcoin.pix.common.security.AuthenticatedUser;
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

    @GetMapping("/me")
    public MeResponse me(AuthenticatedUser user) {
        return MeResponse.from(user);
    }
}
