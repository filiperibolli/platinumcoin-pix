package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.model.PixKey;
import com.platinumcoin.pix.account.domain.usecase.DeletePixKeyUseCase;
import com.platinumcoin.pix.account.domain.usecase.ListPixKeysUseCase;
import com.platinumcoin.pix.account.domain.usecase.RegisterPixKeyUseCase;
import com.platinumcoin.pix.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
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
 * body or path (Domain Safety Rule #1).
 *
 * <p>Per ADR-0011 this class holds <b>no</b> business policy. EVP generation, normalization, format
 * validation, the global-uniqueness outcome and the ownership guard all live in
 * {@code domain/usecase/}; the domain exceptions they raise are mapped to their status + {@code code}
 * by {@link AccountExceptionHandler} ({@code 422 INVALID_PIX_KEY}, {@code 409 KEY_ALREADY_EXISTS},
 * {@code 404 KEY_NOT_FOUND}, {@code 403 KEY_FORBIDDEN}). What is left here is HTTP: bind and
 * bean-validate the body, call one use case, choose the success status and response record.
 */
@RestController
@RequestMapping("/v1/pix-keys")
public class PixKeyController {

    private final RegisterPixKeyUseCase registerPixKey;
    private final ListPixKeysUseCase listPixKeys;
    private final DeletePixKeyUseCase deletePixKey;

    public PixKeyController(RegisterPixKeyUseCase registerPixKey, ListPixKeysUseCase listPixKeys,
            DeletePixKeyUseCase deletePixKey) {
        this.registerPixKey = registerPixKey;
        this.listPixKeys = listPixKeys;
        this.deletePixKey = deletePixKey;
    }

    @PostMapping
    public ResponseEntity<PixKeyResponse> register(@Valid @RequestBody RegisterPixKeyRequest request,
            AuthenticatedUser user) {
        PixKey key = registerPixKey.execute(
                request.keyType(), request.keyValue(), user.accountId(), user.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(PixKeyResponse.from(key));
    }

    @GetMapping
    public List<PixKeyResponse> list(AuthenticatedUser user) {
        return listPixKeys.execute(user.accountId()).stream()
                .map(PixKeyResponse::from)
                .toList();
    }

    @DeleteMapping("/{keyValue}")
    public ResponseEntity<Void> delete(@PathVariable("keyValue") String keyValue, AuthenticatedUser user) {
        deletePixKey.execute(keyValue, user.accountId());
        return ResponseEntity.noContent().build();
    }
}
