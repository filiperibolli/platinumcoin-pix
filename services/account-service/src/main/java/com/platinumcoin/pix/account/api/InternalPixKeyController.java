package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.model.KeyResolution;
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
 * mirror DTO (ADR-0010).
 *
 * <p><b>Three answers since step 30</b>, and telling them apart is the whole value of this endpoint:
 * {@code 200 {internal:true, accountId}} for a key held here, {@code 200 {internal:false, externalBank}}
 * for one held at another participant (BACEN's DICT answered), {@code 404 KEY_NOT_FOUND} only when
 * <i>neither</i> directory knows it. A fourth case is deliberately kept out of that {@code 404}: if the
 * external DICT cannot be reached, the answer is {@code 503 DIRECTORY_UNAVAILABLE} — see
 * {@link AccountExceptionHandler} and {@code ExternalDirectoryUnavailableException}.
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
