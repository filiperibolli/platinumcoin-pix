package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.PixKey;
import com.platinumcoin.pix.account.domain.PixKeyNotFoundException;
import com.platinumcoin.pix.account.domain.PixKeyNotOwnedException;
import com.platinumcoin.pix.account.domain.PixKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delete one of the caller's Pix keys, guarded by ownership. The guard is the whole point of the use
 * case: load the key, compare its {@code accountId} to the caller's, and only then delete. Absent ⇒
 * {@link PixKeyNotFoundException}; owned by someone else ⇒ {@link PixKeyNotOwnedException} (403, not
 * 404 — a Pix key is a globally resolvable identifier, so its existence is not secret).
 */
public class DeletePixKeyUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeletePixKeyUseCase.class);

    private final PixKeyRepository keys;

    public DeletePixKeyUseCase(PixKeyRepository keys) {
        this.keys = keys;
    }

    public void execute(String keyValue, String callerAccountId) {
        log.info("account.key.delete.request accountId={}", callerAccountId);

        PixKey key = keys.findByValue(keyValue)
                .orElseThrow(() -> {
                    log.info("account.key.delete.miss accountId={}", callerAccountId);
                    return new PixKeyNotFoundException("No Pix key found for the given value.");
                });

        if (!key.accountId().equals(callerAccountId)) {
            log.warn("account.key.delete.forbidden accountId={} ownerAccountId={}",
                    callerAccountId, key.accountId());
            throw new PixKeyNotOwnedException();
        }

        keys.delete(keyValue);
        log.info("account.key.delete.done accountId={}", callerAccountId);
    }
}
