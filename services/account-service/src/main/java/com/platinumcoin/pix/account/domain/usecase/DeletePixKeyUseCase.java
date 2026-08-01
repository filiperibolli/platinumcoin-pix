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
        log.info("Pix-key deletion requested | keyValue={} callerAccountId={}",
                keyValue, callerAccountId);

        PixKey key = keys.findByValue(keyValue)
                .orElseThrow(() -> {
                    log.info("No Pix key with this value exists, nothing to delete, returning 404 "
                            + "| keyValue={} callerAccountId={}", keyValue, callerAccountId);
                    return new PixKeyNotFoundException("No Pix key found for the given value.");
                });

        if (!key.accountId().equals(callerAccountId)) {
            log.warn("Pix-key deletion refused, the key belongs to another account, returning 403 "
                            + "| keyValue={} keyType={} callerAccountId={} ownerAccountId={}",
                    keyValue, key.keyType(), callerAccountId, key.accountId());
            throw new PixKeyNotOwnedException();
        }

        keys.delete(keyValue);
        log.info("Pix key deleted | keyValue={} keyType={} accountId={}",
                keyValue, key.keyType(), callerAccountId);
    }
}
