package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.usecase.GetStatementWindowUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code GET /internal/ledger/statement-window} — where the online statement ends
 * and the cold archive begins (step 53).
 *
 * <h2>Why this route exists</h2>
 * payment-service has to answer {@code 422 USE_HOT_STATEMENT} on an export whose whole range is still
 * online, and the boundary that decides it is a property of <b>this</b> service: ledger-service owns
 * {@code pix_ledger} and runs the archiving job, so {@code pix.archive.hot-window-days} is the only
 * place the dial can honestly be turned. The alternative — the same environment variable configured in
 * two services — is one policy constant with two definitions, which is the shape of bug step 52
 * recorded when it refused to let two services each compute a clearing shard.
 *
 * <p>A separate controller from {@link InternalLedgerController} because that one is mapped under
 * {@code /internal/ledger/accounts} and this fact is not account-scoped: the window is the same for
 * every customer, which is precisely why the route takes no id and why a caller may cache the answer.
 *
 * <p>Scoped {@code ledger:read} in {@code jwt.internal-routes} — an unlisted {@code /internal/**} route
 * is refused outright (ADR-0017). It reads no money and no customer data at all, but there is no
 * weaker scope to give it, and inventing one for a single read would be more surface than it saves.
 *
 * <p>Per ADR-0011 the controller binds nothing, decides nothing and calls one use case.
 */
@RestController
@RequestMapping("/internal/ledger")
public class InternalStatementWindowController {

    private final GetStatementWindowUseCase getStatementWindow;

    public InternalStatementWindowController(GetStatementWindowUseCase getStatementWindow) {
        this.getStatementWindow = getStatementWindow;
    }

    @GetMapping("/statement-window")
    public StatementWindowResponse statementWindow() {
        return StatementWindowResponse.from(getStatementWindow.execute());
    }
}
