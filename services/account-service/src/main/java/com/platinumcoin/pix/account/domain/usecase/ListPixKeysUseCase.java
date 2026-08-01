package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.PixKey;
import com.platinumcoin.pix.account.domain.PixKeyRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * List the Pix keys of the caller's account (GSI1 query on {@code ACCOUNT#<accountId>}). The scope
 * is the account from the JWT — there is no "list someone else's keys" operation, and the absence of
 * an {@code accountId} parameter on the {@code api/} side is what guarantees it.
 */
public class ListPixKeysUseCase {

    private static final Logger log = LoggerFactory.getLogger(ListPixKeysUseCase.class);

    private final PixKeyRepository keys;

    public ListPixKeysUseCase(PixKeyRepository keys) {
        this.keys = keys;
    }

    public List<PixKey> execute(String accountId) {
        log.info("account.key.list.lookup accountId={}", accountId);
        List<PixKey> found = keys.listByAccount(accountId);
        log.info("account.key.list.resolved accountId={} count={}", accountId, found.size());
        return found;
    }
}
