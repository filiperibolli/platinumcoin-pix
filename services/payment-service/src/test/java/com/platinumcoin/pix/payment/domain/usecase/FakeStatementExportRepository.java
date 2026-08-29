package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.payment.domain.model.StatementExport;
import com.platinumcoin.pix.payment.domain.model.StatementExportStatus;
import com.platinumcoin.pix.payment.domain.port.StatementExportRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link StatementExportRepository} for the plain-Java use-case tests.
 *
 * <p>It reproduces the two behaviours the use cases actually depend on, and no more: the create is a
 * <b>conditional</b> put that reports a collision instead of overwriting, and the two terminal
 * transitions are <b>guarded</b> on the export still being {@code PENDING}. Getting either of those
 * wrong here would make the tests pass over a repository that cannot exist, which is the standard trap
 * with hand-written doubles — so they are the parts written most carefully.
 */
final class FakeStatementExportRepository implements StatementExportRepository {

    private final Map<String, StatementExport> exports = new ConcurrentHashMap<>();
    private final List<OutboxEvent> published = new ArrayList<>();

    @Override
    public boolean create(StatementExport export, List<OutboxEvent> events) {
        StatementExport existing = exports.putIfAbsent(export.exportId(), export);
        if (existing != null) {
            return false;
        }
        published.addAll(events);
        return true;
    }

    @Override
    public Optional<StatementExport> findById(String exportId) {
        return Optional.ofNullable(exports.get(exportId));
    }

    @Override
    public boolean markReady(String exportId, String downloadKey, Instant completedAt) {
        return transition(exportId, StatementExportStatus.READY, downloadKey, completedAt, null);
    }

    @Override
    public boolean markFailed(String exportId, String failureReason, Instant completedAt) {
        return transition(exportId, StatementExportStatus.FAILED, null, completedAt, failureReason);
    }

    private boolean transition(
            String exportId, StatementExportStatus to, String key, Instant at, String reason) {
        StatementExport current = exports.get(exportId);
        if (current == null || current.isTerminal()) {
            return false;
        }
        exports.put(exportId, new StatementExport(
                current.exportId(), current.accountId(), current.range(), to, current.requestHash(),
                current.requestedAt(), key, at, reason));
        return true;
    }

    /** Every event written alongside a created export — the outbox half of the atomic write. */
    List<OutboxEvent> published() {
        return List.copyOf(published);
    }

    /** Put an export straight into the store, for a test that starts from an existing one. */
    void seed(StatementExport export) {
        exports.put(export.exportId(), export);
    }
}
