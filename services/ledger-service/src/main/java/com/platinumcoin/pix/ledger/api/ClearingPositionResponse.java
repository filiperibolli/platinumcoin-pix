package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.model.ClearingPosition;
import java.math.BigDecimal;
import java.util.List;

/**
 * Wire view of the clearing position (step 52). Same money rule as {@link BalanceResponse}: cents
 * become a decimal string here at the {@code api/} edge and nowhere else, and both representations
 * ship because the two audiences differ — an operator reads {@code balance}, reconciliation and the
 * load study read {@code balanceCents}.
 *
 * @param balance         Σ over every clearing account, as a decimal BRL string
 * @param balanceCents    the same total in integer cents
 * @param shardCount      how many sub-accounts the platform is currently assigning to
 * @param shards          the per-account breakdown, in resolver order
 * @param missingAccounts configured clearing accounts with no BALANCE item — empty is the healthy case
 */
public record ClearingPositionResponse(
        String balance,
        long balanceCents,
        int shardCount,
        List<ShardBalanceResponse> shards,
        List<String> missingAccounts) {

    /** One clearing account's contribution to the total. */
    public record ShardBalanceResponse(
            String accountId, String balance, long balanceCents, long version) {
    }

    static ClearingPositionResponse from(ClearingPosition position, int shardCount) {
        return new ClearingPositionResponse(
                formatCents(position.totalCents()),
                position.totalCents(),
                shardCount,
                position.shards().stream()
                        .map(shard -> new ShardBalanceResponse(
                                shard.accountId(), formatCents(shard.balanceCents()),
                                shard.balanceCents(), shard.version()))
                        .toList(),
                position.missingAccounts());
    }

    /** Exact base-10 shift, so a negative clearing position formats correctly too. */
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
