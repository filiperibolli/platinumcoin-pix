package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.common.security.AuthenticatedUser;
import com.platinumcoin.pix.payment.domain.model.StatementExportStatus;
import com.platinumcoin.pix.payment.domain.usecase.GetStatementExportUseCase;
import com.platinumcoin.pix.payment.domain.usecase.RequestStatementExportCommand;
import com.platinumcoin.pix.payment.domain.usecase.RequestStatementExportOutcome;
import com.platinumcoin.pix.payment.domain.usecase.RequestStatementExportUseCase;
import com.platinumcoin.pix.payment.domain.usecase.StatementExportView;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for the cold statement export (step 53, ARCHITECTURE §6.14): the {@code 202} request
 * and the polling route that eventually hands over a download link.
 *
 * <h2>One controller, two paths, and why there is no class-level mapping</h2>
 * The two routes belong to one resource but live under different roots by design.
 * {@code /v1/accounts/me/statement/exports} is a sub-collection of the caller's own statement — the
 * {@code /me} idiom the balance and statement reads already use, where the account is the token's and
 * there is no path segment a client could point elsewhere (Domain Safety Rule #1). The export itself is
 * then addressed globally at {@code /v1/statement-exports/{exportId}}, because a status URL a client
 * stores and polls should not carry a {@code /me} that means something different depending on who is
 * holding the token. Splitting them across two controllers would put one flow's two halves in two
 * files; a class-level {@code @RequestMapping} cannot cover both roots, so each method carries its
 * whole path.
 *
 * <p>Per ADR-0011 this class does exactly three things: bind and bean-validate the wire shape, call one
 * use case, map the result to HTTP. Every rule about ranges, ownership and idempotency is in the use
 * cases; the only decision here is the {@code Retry-After} on a {@code PENDING} poll, which is a
 * transport-level courtesy rather than policy — it tells a front-end how to poll politely instead of
 * leaving it to invent an interval.
 */
@RestController
public class StatementExportController {

    /**
     * Seconds a client should wait before polling a {@code PENDING} export again. Short enough that a
     * quick export feels immediate, long enough that a browser left open does not become a load test.
     */
    private static final String RETRY_AFTER_SECONDS = "5";

    private final RequestStatementExportUseCase requestStatementExport;
    private final GetStatementExportUseCase getStatementExport;

    public StatementExportController(
            RequestStatementExportUseCase requestStatementExport,
            GetStatementExportUseCase getStatementExport) {
        this.requestStatementExport = requestStatementExport;
        this.getStatementExport = getStatementExport;
    }

    @PostMapping("/v1/accounts/me/statement/exports")
    public ResponseEntity<StatementExportAcceptedResponse> requestExport(
            @Valid @RequestBody StatementExportRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            AuthenticatedUser user) {

        RequestStatementExportOutcome outcome = requestStatementExport.execute(new RequestStatementExportCommand(
                user.accountId(), idempotencyKey, body.fromMonth(), body.toMonth()));

        // 202 on a first request and on a replay alike: the client cannot tell them apart, which is
        // exactly what makes retrying safe to do blindly.
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .location(URI.create(StatementExportAcceptedResponse.statusPath(outcome.exportId())))
                .body(StatementExportAcceptedResponse.from(outcome));
    }

    @GetMapping("/v1/statement-exports/{exportId}")
    public ResponseEntity<StatementExportResponse> status(
            @PathVariable("exportId") String exportId, AuthenticatedUser user) {

        StatementExportView view = getStatementExport.execute(user.accountId(), exportId);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (view.status() == StatementExportStatus.PENDING) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return response.body(StatementExportResponse.from(view));
    }
}
