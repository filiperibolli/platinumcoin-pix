package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.payment.domain.exception.HotWindowExportException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyRequiredException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyReuseException;
import com.platinumcoin.pix.payment.domain.exception.InvalidExportRangeException;
import com.platinumcoin.pix.payment.domain.model.MonthRange;
import com.platinumcoin.pix.payment.domain.model.StatementExport;
import com.platinumcoin.pix.payment.domain.model.StatementWindow;
import com.platinumcoin.pix.payment.domain.port.AccountLimitClient;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
import com.platinumcoin.pix.payment.domain.port.StatementExportRepository;
import com.platinumcoin.pix.payment.domain.service.StatementExportId;
import com.platinumcoin.pix.payment.domain.service.StatementExportOutboxEvents;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accept (or replay) a request for a cold-statement export (step 53, ARCHITECTURE §6.14).
 *
 * <h2>The order of the steps is the design</h2>
 * Everything that can refuse the request runs <b>before</b> anything is written:
 * <ol>
 *   <li>the idempotency key must be present — without it there is no way to make a retry safe, so
 *       there is no way to accept the request at all (ADR-0002);</li>
 *   <li>the range must be well-formed, in order and within the length bound ({@link MonthRange});</li>
 *   <li>it must not reach back before the account existed — those months can only ever be empty;</li>
 *   <li>it must not be <i>entirely</i> inside the hot window, where the data is already available
 *       synchronously.</li>
 * </ol>
 * Validating first is what lets a client fix a bad range and retry with the <b>same</b> key. If the
 * claim came first, a typo would burn a key the client would then have to replace — punishing it for a
 * mistake the platform had already caught, and (worse) making "same key, corrected body" a
 * {@code 409} that looks like a client bug.
 *
 * <h2>Why there is no separate idempotency store</h2>
 * The export id is a pure function of {@code (accountId, key)} ({@link StatementExportId}), so the
 * conditional put of the request item <b>is</b> the claim. A collision is the replay: read the item
 * back, compare the stored fingerprint of the range, and either replay the original answer or refuse
 * the key as reused. One write, one item, and no window in which a key is claimed but its resource does
 * not exist yet. The money path's {@code pix_idempotency} table is deliberately untouched — see
 * {@link StatementExportId} for why borrowing it would mean writing a placeholder into the very field
 * ADR-0014 exists to protect.
 *
 * <p>The request item and the event that wakes the worker go out in one atomic write, so an accepted
 * export is never an export nothing will pick up (ADR-0004).
 */
public class RequestStatementExportUseCase {

    private static final Logger log = LoggerFactory.getLogger(RequestStatementExportUseCase.class);

    private final StatementExportRepository exports;
    private final AccountLimitClient accounts;
    private final LedgerClient ledger;
    private final Clock clock;

    public RequestStatementExportUseCase(
            StatementExportRepository exports,
            AccountLimitClient accounts,
            LedgerClient ledger,
            Clock clock) {
        this.exports = exports;
        this.accounts = accounts;
        this.ledger = ledger;
        this.clock = clock;
    }

    public RequestStatementExportOutcome execute(RequestStatementExportCommand command) {
        log.info("Statement export requested | accountId={} fromMonth={} toMonth={} idempotencyKey={}",
                command.accountId(), command.fromMonth(), command.toMonth(), command.idempotencyKey());

        String key = requireIdempotencyKey(command);
        MonthRange range = MonthRange.parse(command.fromMonth(), command.toMonth());
        refuseIfBeforeTheAccountExisted(command.accountId(), range);
        refuseIfEntirelyHot(range);

        String exportId = StatementExportId.of(command.accountId(), key);
        String requestHash = StatementExportId.requestHash(range.from().toString(), range.to().toString());
        Instant now = clock.instant();

        StatementExport export =
                StatementExport.pending(exportId, command.accountId(), range, requestHash, now);
        List<OutboxEvent> events = StatementExportOutboxEvents.forAcceptedRequest(export, now);

        if (exports.create(export, events)) {
            log.info("Statement export accepted and queued, the request item and its event were written "
                            + "in one atomic transaction | exportId={} accountId={} fromMonth={} "
                            + "toMonth={} months={} eventId={}",
                    exportId, command.accountId(), range.from(), range.to(), range.months().size(),
                    events.getFirst().eventId());
            return new RequestStatementExportOutcome(exportId, export.status(), false);
        }

        return replay(command, exportId, requestHash);
    }

    /**
     * The conditional put lost, so an export already exists under this id — which can only mean this
     * account has used this idempotency key before. Two cases, told apart by the stored fingerprint.
     */
    private RequestStatementExportOutcome replay(
            RequestStatementExportCommand command, String exportId, String requestHash) {
        StatementExport existing = exports.findById(exportId).orElseThrow(() ->
                // Not reachable through any normal path: the create failed *because* the item is there.
                // It would take a delete of a request item, which nothing in the platform does.
                new IllegalStateException("export " + exportId + " collided on create but cannot be read"));

        if (!existing.requestHash().equals(requestHash)) {
            log.warn("Statement export refused, this idempotency key was already used for a different "
                            + "month range, returning 409 | exportId={} accountId={} requestedFrom={} "
                            + "requestedTo={} storedFrom={} storedTo={}",
                    exportId, command.accountId(), command.fromMonth(), command.toMonth(),
                    existing.range().from(), existing.range().to());
            throw new IdempotencyKeyReuseException(
                    "this Idempotency-Key was already used for the range " + existing.range().from()
                            + ".." + existing.range().to());
        }

        log.info("Statement export request replayed, the original export is returned unchanged and no "
                        + "second job was queued | exportId={} accountId={} status={} fromMonth={} "
                        + "toMonth={}",
                exportId, command.accountId(), existing.status(), existing.range().from(),
                existing.range().to());
        return new RequestStatementExportOutcome(exportId, existing.status(), true);
    }

    private String requireIdempotencyKey(RequestStatementExportCommand command) {
        String key = command.idempotencyKey();
        if (key == null || key.isBlank()) {
            log.warn("Statement export refused, the Idempotency-Key header is missing, returning 400 | "
                    + "accountId={}", command.accountId());
            throw new IdempotencyKeyRequiredException();
        }
        return key.trim();
    }

    private void refuseIfBeforeTheAccountExisted(String accountId, MonthRange range) {
        YearMonth openedMonth = YearMonth.from(accounts.openedAt(accountId).atZone(ZoneOffset.UTC));
        if (range.startsBefore(openedMonth)) {
            log.warn("Statement export refused, the range starts before the account existed, returning "
                            + "422 | accountId={} fromMonth={} toMonth={} accountOpenedMonth={}",
                    accountId, range.from(), range.to(), openedMonth);
            throw new InvalidExportRangeException("fromMonth " + range.from()
                    + " is before the account was opened (" + openedMonth + ").");
        }
    }

    private void refuseIfEntirelyHot(MonthRange range) {
        StatementWindow window = ledger.statementWindow();
        if (range.isEntirelyHot(window.newestColdMonth())) {
            log.warn("Statement export refused, the whole range is still inside the hot window and is "
                            + "already available synchronously, returning 422 | fromMonth={} toMonth={} "
                            + "newestColdMonth={} hotWindowDays={} coldBefore={}",
                    range.from(), range.to(), window.newestColdMonth(), window.hotWindowDays(),
                    window.coldBefore());
            throw new HotWindowExportException("Months " + range.from() + ".." + range.to()
                    + " are still in the online statement; read GET /v1/accounts/me/statement instead.");
        }
    }
}
