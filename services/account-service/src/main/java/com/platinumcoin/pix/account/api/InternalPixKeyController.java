package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.KeyResolution;
import com.platinumcoin.pix.account.domain.usecase.ResolvePixKeyUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code GET /internal/pix-keys/resolve?key=…} — account-service's <b>DICT</b>
 * role for keys that live inside PlatinumCoin. Like {@link InternalAccountController}, it is a
 * service-to-service seam (ADR-0006): NOT on the public allow-list, so it sits behind the shared
 * {@code JwtAuthFilter} and requires a valid token. A deployed posture would gate it with a service
 * credential/scope or mTLS rather than an end-user token (step-45 hardening).
 *
 * <p>Returns the {@link KeyResolution} record directly — the wire shape
 * {@code {internal, accountId?, externalBank?, keyType}} is identical to the domain result, so no
 * mirror DTO (ADR-0010). An unresolvable key becomes {@code 404 KEY_NOT_FOUND} via
 * {@link AccountExceptionHandler}; the external-delegation branch is a stub until step 30 (see
 * {@code ResolvePixKeyUseCase#resolveExternal}).
 */
@RestController
@RequestMapping("/internal/pix-keys")
public class InternalPixKeyController {

    private final ResolvePixKeyUseCase resolvePixKey;

    public InternalPixKeyController(ResolvePixKeyUseCase resolvePixKey) {
        this.resolvePixKey = resolvePixKey;
    }

    @GetMapping("/resolve")
    public KeyResolution resolve(@RequestParam("key") String key) {
        return resolvePixKey.execute(key);
    }
}
