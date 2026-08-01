package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.InvalidPixKeyException;
import com.platinumcoin.pix.account.domain.PixKey;
import com.platinumcoin.pix.account.domain.PixKeyAlreadyExistsException;
import com.platinumcoin.pix.account.domain.PixKeyRepository;
import com.platinumcoin.pix.account.domain.PixKeyType;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Register a Pix key for the caller's account. This is the use case that motivated ADR-0011: every
 * rule below used to live inside a Spring controller.
 *
 * <ol>
 *   <li><b>EVP is server-generated.</b> The value is a UUID minted here and the client's
 *       {@code rawValue} is ignored entirely — a security rule of the same family as Domain Safety
 *       Rule #1 (the client does not choose), so it belongs in the domain, not in an HTTP adapter.</li>
 *   <li><b>Normalize before validating</b>, because the normalized form is what becomes the
 *       global-uniqueness key (EMAIL is lowercased, so casing cannot duplicate a key).</li>
 *   <li><b>Format-validate</b> ({@link PixKeyType#matches}) ⇒ {@link InvalidPixKeyException}.</li>
 *   <li><b>Conditional put</b> — the port returns {@code false} when the value is already taken
 *       globally ⇒ {@link PixKeyAlreadyExistsException}. Never a read-then-write check: the
 *       condition lives inside the write, so two racing registrations cannot both win.</li>
 * </ol>
 *
 * <p>The {@link Clock} and the UUID supplier are injected rather than called statically, so a test
 * can pin both and assert the stored key exactly (ADR-0011: no {@code Instant.now()} in a handler).
 */
public class RegisterPixKeyUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterPixKeyUseCase.class);

    private final PixKeyRepository keys;
    private final Clock clock;
    private final Supplier<String> evpValueGenerator;

    public RegisterPixKeyUseCase(PixKeyRepository keys, Clock clock) {
        this(keys, clock, () -> UUID.randomUUID().toString());
    }

    RegisterPixKeyUseCase(PixKeyRepository keys, Clock clock, Supplier<String> evpValueGenerator) {
        this.keys = keys;
        this.clock = clock;
        this.evpValueGenerator = evpValueGenerator;
    }

    public PixKey execute(PixKeyType type, String rawValue, String accountId, String userId) {
        log.info("account.key.register.request keyType={} accountId={}", type, accountId);

        String value = type.isServerGenerated()
                ? evpValueGenerator.get()
                : type.normalize(rawValue);

        if (!type.matches(value)) {
            log.warn("account.key.register.invalid keyType={} accountId={}", type, accountId);
            throw new InvalidPixKeyException(type);
        }

        PixKey key = new PixKey(type, value, accountId, userId, Instant.now(clock));
        if (!keys.register(key)) {
            log.warn("account.key.register.duplicate keyType={} accountId={}", type, accountId);
            throw new PixKeyAlreadyExistsException();
        }

        log.info("account.key.register.created keyType={} accountId={}", type, accountId);
        return key;
    }
}
