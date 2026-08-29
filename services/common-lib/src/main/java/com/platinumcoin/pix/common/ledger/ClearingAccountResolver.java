package com.platinumcoin.pix.common.ledger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import java.util.zip.CRC32;

/**
 * Which clearing sub-account an external transfer parks its money in (step 52).
 *
 * <h2>The problem being solved</h2>
 * Every external send credits one ledger item, {@code ACCOUNT#SPI_CLEARING / BALANCE}. A DynamoDB
 * partition caps at 1,000 WCU/s and a transactional write costs 2x WCU, so that single item ceilings at
 * roughly 500 transactional updates/s — below the 500 TPS the Black Friday profile aims at, and the
 * platform would hit it as throttling on the *credit leg of a payment*, not on anything a user did
 * wrong. Write sharding spreads those writes over N items whose logical sum is the clearing position:
 * {@code SPI_CLEARING#00..#15} instead of {@code SPI_CLEARING}.
 *
 * <h2>Why the caller resolves, and not the ledger</h2>
 * ARCHITECTURE §6.3 called this out before the code existed: the ledger's posting contract takes an
 * explicit {@code debitAccount}/{@code creditAccount}, so introducing shards changes only <i>which id
 * the caller passes</i> — the posting, the guards and the invariants are untouched. A ledger that
 * rewrote the account id it was handed would be a ledger whose entries no longer say what its callers
 * asked for, and every reversal would have to trust that rewrite to be identical months later. This
 * class therefore lives in common-lib, shared by the two services that <i>choose</i> a clearing
 * account (payment-service on an outbound debit, settlement-service on an inbound credit) and used by
 * ledger-service only to <i>enumerate</i> the set it sums. One definition of the mapping, three users:
 * two implementations of "which shard" is how money ends up in a shard nobody compensates.
 *
 * <h2>The sharp edge: a reversal must hit the shard that was credited</h2>
 * Nothing here is called on the reversal path. A compensating posting reads the exact account id off
 * the transaction ({@code clearingAccountId}, persisted at debit time since step 33) and never
 * re-derives it. That is deliberate and it is the whole reason the field exists: re-deriving would make
 * the correctness of a months-old reversal depend on {@code CLEARING_SHARDS} still holding the value it
 * had when the debit committed. Change N from 16 to 32 and every in-flight transaction would reverse
 * against the wrong sub-account — each posting individually balanced, Σ over all accounts unchanged,
 * and one shard silently drained into another. Persisting the id makes N a capacity knob instead of a
 * correctness-critical constant.
 *
 * <h2>Why CRC32 and not {@code String.hashCode()}</h2>
 * The mapping has to agree across three JVMs and across releases. {@code String.hashCode()} is
 * specified and would do that, but it is a weak hash over structured inputs — every txId here is
 * {@code "tx-" + UUID} or {@code "in-" + endToEndId}, long shared prefixes with low-entropy tails —
 * and {@code hash % 16} keeps only the bottom four bits. {@link CRC32} is equally specified, avalanches
 * those inputs properly, and costs nanoseconds. It is not a security choice: nothing here resists an
 * adversary picking txIds, and nothing needs to — a caller who could concentrate their own payments on
 * one shard would slow themselves down, not move anyone's money.
 */
public final class ClearingAccountResolver {

    /** Two digits is the whole reason for the cap: {@code SPI_CLEARING#100} would not sort or format. */
    private static final int MAX_SHARDS = 100;

    private final String baseAccountId;
    private final int shards;
    private final List<String> allShards;

    /**
     * @param baseAccountId the logical clearing account ({@code SPI_CLEARING}); with {@code shards == 1}
     *                      this id is returned unchanged, which is exactly the pre-step-52 behaviour and
     *                      what the findings doc's baseline run measures
     * @param shards        how many sub-accounts to spread the writes over, {@code 1..100}
     */
    public ClearingAccountResolver(String baseAccountId, int shards) {
        if (baseAccountId == null || baseAccountId.isBlank()) {
            throw new IllegalArgumentException("clearing account id must not be blank");
        }
        if (shards < 1 || shards > MAX_SHARDS) {
            throw new IllegalArgumentException(
                    "clearing shards must be between 1 and " + MAX_SHARDS + ", got " + shards);
        }
        this.baseAccountId = baseAccountId;
        this.shards = shards;
        this.allShards = shards == 1
                ? List.of(baseAccountId)
                : IntStream.range(0, shards)
                        .mapToObj(i -> "%s#%02d".formatted(baseAccountId, i))
                        .toList();
    }

    /**
     * The clearing account this operation's money goes into. Keyed by {@code txId} — the durable
     * operation identity minted before the idempotency claim (ADR-0014) — so a resumed request resolves
     * to the same shard it resolved to the first time, and the ledger's {@code txId} guard still sees
     * one operation rather than two.
     */
    public String shardFor(String txId) {
        if (txId == null || txId.isBlank()) {
            throw new IllegalArgumentException("txId must not be blank");
        }
        if (shards == 1) {
            return baseAccountId;
        }
        var crc = new CRC32();
        crc.update(txId.getBytes(StandardCharsets.UTF_8));
        return allShards.get((int) (crc.getValue() % shards));
    }

    /**
     * Every clearing account in play, in order — the set the logical clearing balance is summed over
     * ({@code GET /internal/ledger/clearing-balance}) and the set the seed script has to create, since
     * the ledger's credit leg is conditioned on {@code attribute_exists(pk)} and will refuse to post
     * into a balance item nobody made.
     */
    public List<String> allShards() {
        return allShards;
    }

    /**
     * Every account clearing money can be sitting in — {@link #allShards()} <b>plus the un-sharded base
     * id</b> when sharding is on. This is the set to SUM, and it is deliberately larger than the set to
     * assign to.
     *
     * <p>The difference matters exactly once, and it is the moment that would otherwise go unnoticed:
     * the shard count is changed while payments are in flight. Those payments were credited under the
     * old map, so their money is in accounts the new map may never hand out again — including the bare
     * {@code SPI_CLEARING} of a platform that ran un-sharded yesterday. They will still reverse
     * correctly (the account id is on the transaction), but a clearing position that summed only the
     * currently-assignable shards would report a number that is missing them, and "clearing is zero"
     * would become a lie told by the platform to itself. Summing the base as well costs one extra read
     * and makes the answer complete for that transition.
     *
     * <p><b>The protection is one-directional, and that is a known limitation rather than an
     * oversight.</b> Raising N (or turning sharding on) is covered: the accounts the old map used are
     * still enumerated. <b>Lowering</b> N is not — drop from 16 to 4 and the money sitting in
     * {@code #04..#15} vanishes from this set, silently, without even appearing in the position's
     * {@code missingAccounts}. It still <i>reverses</i> correctly, because the reversal reads the
     * account id off the transaction rather than from here; it simply stops being visible. Making the
     * read robust in both directions would mean a prefix scan of the whole balance space on every call
     * to an operational endpoint, which is a real cost paid on every request to cover an operation
     * nobody performs during traffic. <b>So the rule is: lowering {@code CLEARING_SHARDS} requires
     * draining the clearing position to zero first.</b> The bash verifiers that run offline
     * ({@code scripts/e2e-journey.sh}, {@code tools/k6/run-s5.sh}) DO use the prefix scan, precisely
     * because they can afford it and a verification script must not share a blind spot with the thing
     * it verifies.
     */
    public List<String> clearingAccounts() {
        if (shards == 1) {
            return allShards;
        }
        var accounts = new java.util.ArrayList<String>(shards + 1);
        accounts.add(baseAccountId);
        accounts.addAll(allShards);
        return List.copyOf(accounts);
    }

    /** How many sub-accounts this resolver spreads over; {@code 1} means sharding is off. */
    public int shardCount() {
        return shards;
    }
}
