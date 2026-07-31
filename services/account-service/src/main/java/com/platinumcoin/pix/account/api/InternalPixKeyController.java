package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.KeyResolution;
import com.platinumcoin.pix.account.domain.KeyResolutionService;
import com.platinumcoin.pix.common.error.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
 * mirror DTO (ADR-0010). Unknown keys are {@code 404 KEY_NOT_FOUND}; the external-delegation branch is
 * a stub until step 30 (see {@code KeyResolutionService#resolveExternal}).
 */
@RestController
@RequestMapping("/internal/pix-keys")
public class InternalPixKeyController {

    private static final Logger log = LoggerFactory.getLogger(InternalPixKeyController.class);

    private final KeyResolutionService resolver;

    public InternalPixKeyController(KeyResolutionService resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/resolve")
    public KeyResolution resolve(@RequestParam("key") String key) {
        log.info("account.key.resolve.request");
        KeyResolution resolution = resolver.resolve(key)
                .orElseThrow(() -> {
                    // No local key and (until step 30) no external DICT — an ordinary lookup miss, so
                    // INFO keeps the correlationId trace complete rather than ERROR.
                    log.info("account.key.resolve.miss");
                    return new DomainException("KEY_NOT_FOUND", HttpStatus.NOT_FOUND,
                            "No account found for the given Pix key.");
                });
        log.info("account.key.resolve.internal internal={} accountId={} keyType={}",
                resolution.internal(), resolution.accountId(), resolution.keyType());
        return resolution;
    }
}
