package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.StatementExport;
import com.platinumcoin.pix.payment.domain.port.ProcessedEvents;
import com.platinumcoin.pix.payment.domain.port.StatementArchiveReader;
import com.platinumcoin.pix.payment.domain.port.StatementExportArtifactStore;
import com.platinumcoin.pix.payment.domain.port.StatementExportRepository;
import com.platinumcoin.pix.payment.domain.service.StatementCsv;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assemble a cold-statement export: read the archive months, merge them into one CSV, store it, and
 * move the request to {@code READY} (step 53, ARCHITECTURE §6.14).
 *
 * <h2>Three defences against doing it twice, and why one is not enough</h2>
 * Delivery is at-least-once and two instances of this service may be handed the same export, so
 * "exactly once" has to be built rather than assumed. Each of the three answers a case the others do
 * not:
 * <ul>
 *   <li><b>The {@code eventId} claim</b> (Domain Safety Rule #2) stops the same <i>message</i> being
 *       worked twice. It is the cheap check in front of an expensive one — without it, a redelivery
 *       would re-read a two-year range out of object storage to discover it had nothing to do.</li>
 *   <li><b>The export's own status</b> stops a <i>different</i> message about a finished export from
 *       doing anything. Two deliveries can carry different event ids (a republish after a crash in the
 *       publisher's mark-published gap does exactly that), and the claim cannot see that they are the
 *       same job.</li>
 *   <li><b>The guarded {@code PENDING → READY} transition</b> stops two workers running
 *       <i>concurrently</i> from both recording a completion. Exactly one wins; the loser is told so
 *       by a return value, because losing is expected rather than exceptional.</li>
 * </ul>
 * The artifact itself needs no defence: its key is a pure function of the export, so a second write
 * replaces the same object with the same bytes.
 *
 * <h2>The claim is given back when the work does not finish</h2>
 * A failed attempt releases the {@code eventId}, or the redelivery it is counting on would be deduped
 * away and the export would sit {@code PENDING} for ever. And because a process can die between the
 * claim and the release, a duplicate delivery of an export that is <i>still {@code PENDING}</i> is
 * worked anyway rather than skipped — the status is the honest signal about whether the job is done,
 * and the claim is only an optimisation over it.
 *
 * <h2>Bounded attempts, then a visible failure</h2>
 * A transient failure (object storage having a moment) is retried by leaving the message on the queue.
 * Once {@code ApproximateReceiveCount} reaches the budget, the export becomes {@code FAILED} with a
 * reason the customer can read — which is a far better answer than a request that stays {@code PENDING}
 * for ever, and it is the difference between a queue's DLQ (an operator's problem) and an API's
 * lifecycle (a customer's answer). The DLQ still backs the cases this cannot reach: a message whose
 * body will not parse never gets here at all.
 */
public class BuildStatementExportUseCase {

    private static final Logger log = LoggerFactory.getLogger(BuildStatementExportUseCase.class);

    private final StatementExportRepository exports;
    private final StatementArchiveReader archive;
    private final StatementExportArtifactStore artifacts;
    private final ProcessedEvents processedEvents;
    private final int maxAttempts;
    private final Clock clock;

    public BuildStatementExportUseCase(
            StatementExportRepository exports,
            StatementArchiveReader archive,
            StatementExportArtifactStore artifacts,
            ProcessedEvents processedEvents,
            int maxAttempts,
            Clock clock) {
        this.exports = exports;
        this.archive = archive;
        this.artifacts = artifacts;
        this.processedEvents = processedEvents;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
    }

    public BuildStatementExportOutcome execute(BuildStatementExportCommand command) {
        String exportId = command.exportId();
        Optional<StatementExport> found = exports.findById(exportId);

        if (found.isEmpty()) {
            log.error("An export request event names an export that is not in the store, nothing can be "
                            + "assembled and the message is left for the dead-letter queue | exportId={} "
                            + "eventId={} deliveryAttempt={}",
                    exportId, command.eventId(), command.deliveryAttempt());
            processedEvents.release(command.eventId());
            return BuildStatementExportOutcome.of(
                    BuildStatementExportOutcome.Result.UNKNOWN_EXPORT, exportId);
        }

        StatementExport export = found.get();

        if (export.isTerminal()) {
            log.info("Export request delivery ignored, this export already reached a terminal state and "
                            + "no second artifact will be produced | exportId={} status={} eventId={} "
                            + "deliveryAttempt={}",
                    exportId, export.status(), command.eventId(), command.deliveryAttempt());
            return BuildStatementExportOutcome.of(
                    BuildStatementExportOutcome.Result.ALREADY_TERMINAL, exportId);
        }

        if (!processedEvents.claim(command.eventId())) {
            // Claimed but still PENDING: a previous holder took the claim and never finished (it died,
            // or its release was lost). Skipping here would strand the export, so the work is done
            // again — safe, because the guarded transition below is what actually keeps it single.
            log.warn("This export event was already claimed but its export is still PENDING, a previous "
                            + "attempt must have died mid-work, so it is assembled again rather than "
                            + "deduped away | exportId={} eventId={} deliveryAttempt={}",
                    exportId, command.eventId(), command.deliveryAttempt());
        }

        log.info("Assembling a cold statement export from the archive | exportId={} accountId={} "
                        + "fromMonth={} toMonth={} months={} deliveryAttempt={} maxAttempts={}",
                exportId, export.accountId(), export.range().from(), export.range().to(),
                export.range().months().size(), command.deliveryAttempt(), maxAttempts);

        try {
            return assemble(export, command);
        } catch (RuntimeException failure) {
            return handleFailure(export, command, failure);
        }
    }

    /**
     * Read the range and write the artifact <b>at the same time</b>, one line at a time.
     *
     * <p>The obvious version of this method collects every month into a list, renders one CSV and
     * uploads it. That version is a latent outage: the cold archive is the tier explicitly allowed to
     * be large, and this worker runs in the JVM that serves {@code POST /v1/payments/pix}, so one
     * customer's two-year export would be an {@code OutOfMemoryError} that takes the money path with
     * it. Streaming bounds the whole flow's memory to one line plus the sink's flush buffer, whatever
     * the export's size.
     *
     * <p>The try-with-resources is load-bearing rather than tidy: a failure part-way through must
     * <b>abort</b> the artifact, because an abandoned multipart upload leaves parts that keep costing
     * storage and never appear in a bucket listing.
     */
    private BuildStatementExportOutcome assemble(
            StatementExport export, BuildStatementExportCommand command) {
        String objectKey;
        int lines = 0;
        int monthsWithData = 0;

        try (StatementExportArtifactStore.Sink sink =
                     artifacts.open(export.accountId(), export.exportId())) {
            // The header goes out before the first read, so an export with nothing in it still produces
            // a file a spreadsheet can open (a month with no movement is skipped, not failed).
            sink.append(StatementCsv.header());

            for (YearMonth month : export.range().months()) {
                int written = archive.stream(export.accountId(), month,
                        line -> sink.append(StatementCsv.row(line)));
                if (written == 0) {
                    log.debug("No archive object for this month, skipping it | exportId={} accountId={} "
                            + "month={}", export.exportId(), export.accountId(), month);
                    continue;
                }
                monthsWithData++;
                lines += written;
            }
            objectKey = sink.finish();
        }

        Instant completedAt = clock.instant();

        if (!exports.markReady(export.exportId(), objectKey, completedAt)) {
            // Another worker finished this export between the status check above and here. The artifact
            // it wrote and the one just written are the same object with the same bytes, so there is
            // nothing to undo — only a completion not to record twice.
            log.info("Export was completed by another delivery while this one was assembling it, the "
                            + "artifact is identical and this delivery records nothing | exportId={} "
                            + "eventId={}",
                    export.exportId(), command.eventId());
            return BuildStatementExportOutcome.of(
                    BuildStatementExportOutcome.Result.ALREADY_TERMINAL, export.exportId());
        }

        log.info("Cold statement export is READY, the artifact was streamed into object storage and the "
                        + "request resource now points at it | exportId={} accountId={} lines={} "
                        + "monthsWithData={} monthsRequested={} objectKey={} completedAt={}",
                export.exportId(), export.accountId(), lines, monthsWithData,
                export.range().months().size(), objectKey, completedAt);
        return BuildStatementExportOutcome.built(export.exportId(), lines, objectKey);
    }

    private BuildStatementExportOutcome handleFailure(
            StatementExport export, BuildStatementExportCommand command, RuntimeException failure) {
        String exportId = export.exportId();

        if (command.deliveryAttempt() < maxAttempts) {
            // Give the claim back, or the redelivery this return value is asking for would be deduped
            // away by the gate at the top and the export would never be assembled.
            processedEvents.release(command.eventId());
            log.warn("Export assembly failed, the message is left on the queue for another attempt and "
                            + "the export stays PENDING | exportId={} deliveryAttempt={} maxAttempts={} "
                            + "error={}",
                    exportId, command.deliveryAttempt(), maxAttempts, failure.toString());
            return BuildStatementExportOutcome.of(
                    BuildStatementExportOutcome.Result.RETRY_LATER, exportId);
        }

        String reason = failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
        exports.markFailed(exportId, reason, clock.instant());
        log.error("Export assembly failed on its last attempt, the export is now FAILED with a reason "
                        + "the customer can read rather than PENDING for ever | exportId={} "
                        + "accountId={} deliveryAttempt={} maxAttempts={} failureReason={}",
                exportId, export.accountId(), command.deliveryAttempt(), maxAttempts, reason, failure);
        return BuildStatementExportOutcome.failed(exportId, reason);
    }
}
