package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.common.ledger.ClearingAccountResolver;
import com.platinumcoin.pix.ledger.domain.usecase.GetClearingPositionUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code GET /internal/ledger/clearing-balance} — the platform's clearing position
 * as one number (step 52, task 3).
 *
 * <p>Sharding took away a read that used to be trivial. "Is the clearing account empty?" is the single
 * most useful sentence about an instant-payment platform's health — it means every payment that left a
 * payer has either settled out or come back — and after step 52 no one item answers it. This route
 * restores the question at the same cost to the caller, and adds the per-shard breakdown that only
 * exists now: a total of zero made of {@code +500} and {@code -500} is a reversal that hit the wrong
 * sub-account, and it is invisible in the total.
 *
 * <p>Path note: the step file writes {@code /internal/clearing-balance}; this service namespaces every
 * internal route under {@code /internal/ledger/**} (the balance and posting ports both do), and staying
 * consistent with the service beat matching the step's shorthand. {@code docs/api/openapi.yaml} carries
 * the path that exists.
 *
 * <p>Per ADR-0011 the controller binds nothing, decides nothing and calls one use case; the summing
 * rule, the missing-account policy and the logging live behind {@link GetClearingPositionUseCase}. The
 * route is scoped {@code ledger:read} in {@code jwt.internal-routes} — an unlisted {@code /internal/**}
 * route is refused outright (ADR-0017), so a read of the platform's money position cannot be opened by
 * forgetting to configure it.
 */
@RestController
@RequestMapping("/internal/ledger")
public class InternalClearingController {

    private final GetClearingPositionUseCase getClearingPosition;
    private final ClearingAccountResolver clearing;

    public InternalClearingController(
            GetClearingPositionUseCase getClearingPosition, ClearingAccountResolver clearing) {
        this.getClearingPosition = getClearingPosition;
        this.clearing = clearing;
    }

    @GetMapping("/clearing-balance")
    public ClearingPositionResponse clearingBalance() {
        return ClearingPositionResponse.from(getClearingPosition.execute(), clearing.shardCount());
    }
}
