package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.PixKey;
import com.platinumcoin.pix.account.domain.PixKeyRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link PixKeyRepository} for the use-case unit tests. It is a <b>fake</b>, not a mock:
 * it really stores keys, so {@link #register} can reproduce the one behaviour the use cases depend
 * on — the conditional put returning {@code false} when the value is already taken globally, by any
 * account. That is what makes these tests meaningful without LocalStack.
 *
 * <p>Everything the DynamoDB adapter does beyond that (the {@code ConditionalCheckFailedException}
 * itself, item shapes, GSI projections) stays covered by {@code PixKeyRepositoryIT}.
 */
final class FakePixKeyRepository implements PixKeyRepository {

    private final Map<String, PixKey> byValue = new LinkedHashMap<>();

    FakePixKeyRepository(PixKey... seeded) {
        for (PixKey key : seeded) {
            byValue.put(key.keyValue(), key);
        }
    }

    @Override
    public Optional<PixKey> findByValue(String keyValue) {
        return Optional.ofNullable(byValue.get(keyValue));
    }

    /** Conditional put: succeeds only when nothing is registered for this value yet. */
    @Override
    public boolean register(PixKey key) {
        return byValue.putIfAbsent(key.keyValue(), key) == null;
    }

    @Override
    public List<PixKey> listByAccount(String accountId) {
        List<PixKey> found = new ArrayList<>();
        byValue.values().stream().filter(k -> k.accountId().equals(accountId)).forEach(found::add);
        return found;
    }

    @Override
    public void delete(String keyValue) {
        byValue.remove(keyValue);
    }

    /** Test-only view: is anything registered for this value? */
    boolean contains(String keyValue) {
        return byValue.containsKey(keyValue);
    }
}
