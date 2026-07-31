package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.PixKey;
import com.platinumcoin.pix.account.domain.PixKeyRepository;
import com.platinumcoin.pix.account.domain.PixKeyType;
import com.platinumcoin.pix.common.error.DomainException;
import com.platinumcoin.pix.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for the Pix-key endpoints (docs/data-model.md §2, OpenAPI {@code /pix-keys*}). The
 * owning account/user always come from the validated JWT ({@link AuthenticatedUser}) — never the
 * body or path (Domain Safety Rule #1). The controller orchestrates the domain + repository and maps
 * outcomes to HTTP, keeping the domain framework-free (ADR-0010):
 *
 * <ul>
 *   <li><b>register</b> — normalize/generate the value, format-validate it ({@code 422} on failure),
 *       then the conditional-put returns {@code false} on a taken value ⇒ {@code 409}.</li>
 *   <li><b>list</b> — GSI1 query scoped to the caller's account.</li>
 *   <li><b>delete</b> — ownership-guarded: {@code 404} if absent, {@code 403} if it belongs to
 *       another account. The asymmetry with payments (which return {@code 404} for a foreign
 *       resource) is deliberate: Pix keys are globally resolvable, so their existence is not secret.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/pix-keys")
public class PixKeyController {

    private static final Logger log = LoggerFactory.getLogger(PixKeyController.class);

    private final PixKeyRepository keys;

    public PixKeyController(PixKeyRepository keys) {
        this.keys = keys;
    }

    @PostMapping
    public ResponseEntity<PixKeyResponse> register(@Valid @RequestBody RegisterPixKeyRequest request,
            AuthenticatedUser user) {
        PixKeyType type = request.keyType();
        // EVP is server-generated: mint a UUID and ignore whatever the client sent as keyValue.
        String value = type.isServerGenerated()
                ? UUID.randomUUID().toString()
                : type.normalize(request.keyValue());
        log.info("account.key.register.request keyType={} accountId={}", type, user.accountId());

        if (!type.matches(value)) {
            // Well-formed JSON, but the value is not a valid instance of its type — 422, not 400.
            log.warn("account.key.register.invalid keyType={} accountId={}", type, user.accountId());
            throw new DomainException("INVALID_PIX_KEY", HttpStatus.UNPROCESSABLE_ENTITY,
                    "The keyValue is not a valid " + type + ".");
        }

        PixKey key = new PixKey(type, value, user.accountId(), user.userId(), Instant.now());
        if (!keys.register(key)) {
            // The conditional put lost the race for this value — it is already registered globally.
            log.warn("account.key.register.duplicate keyType={} accountId={}", type, user.accountId());
            throw new DomainException("KEY_ALREADY_EXISTS", HttpStatus.CONFLICT,
                    "This Pix key is already registered.");
        }

        log.info("account.key.register.created keyType={} accountId={}", type, user.accountId());
        return ResponseEntity.status(HttpStatus.CREATED).body(PixKeyResponse.from(key));
    }

    @GetMapping
    public List<PixKeyResponse> list(AuthenticatedUser user) {
        log.info("account.key.list.lookup accountId={}", user.accountId());
        List<PixKeyResponse> response = keys.listByAccount(user.accountId()).stream()
                .map(PixKeyResponse::from)
                .toList();
        log.info("account.key.list.resolved accountId={} count={}", user.accountId(), response.size());
        return response;
    }

    @DeleteMapping("/{keyValue}")
    public ResponseEntity<Void> delete(@PathVariable("keyValue") String keyValue, AuthenticatedUser user) {
        log.info("account.key.delete.request accountId={}", user.accountId());
        PixKey key = keys.findByValue(keyValue)
                .orElseThrow(() -> {
                    log.info("account.key.delete.miss accountId={}", user.accountId());
                    return new DomainException("KEY_NOT_FOUND", HttpStatus.NOT_FOUND,
                            "No Pix key found for the given value.");
                });

        if (!key.accountId().equals(user.accountId())) {
            // Ownership guard. 403 (not 404) on purpose: the key exists and is globally resolvable, so
            // revealing its existence leaks nothing — unlike a foreign transactionId, which 404s.
            log.warn("account.key.delete.forbidden accountId={} ownerAccountId={}",
                    user.accountId(), key.accountId());
            throw new DomainException("KEY_FORBIDDEN", HttpStatus.FORBIDDEN,
                    "This Pix key belongs to another account.");
        }

        keys.delete(keyValue);
        log.info("account.key.delete.done accountId={}", user.accountId());
        return ResponseEntity.noContent().build();
    }
}
