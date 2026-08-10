package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.model.PixKey;
import com.platinumcoin.pix.account.domain.port.PixKeyRepository;
import java.util.List;
import java.util.stream.Collectors;
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
        log.info("Listing the Pix keys of the caller's account | accountId={}", accountId);
        List<PixKey> found = keys.listByAccount(accountId);
        // The INFO line answers "did the list work and how big is it"; the keys themselves go to
        // DEBUG — same information, one level down, so a busy trace stays readable (ADR-0012).
        log.info("Listed the Pix keys of the caller's account | accountId={} count={}",
                accountId, found.size());
        log.debug("Pix keys returned to the caller | accountId={} keys=[{}]", accountId, found.stream()
                .map(k -> k.keyType() + ":" + k.keyValue())
                .collect(Collectors.joining(", ")));
        return found;
    }
}
