package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.ArchivedStatementLine;
import com.platinumcoin.pix.payment.domain.model.Direction;
import com.platinumcoin.pix.payment.domain.model.DownloadLink;
import com.platinumcoin.pix.payment.domain.port.ProcessedEvents;
import com.platinumcoin.pix.payment.domain.port.StatementArchiveReader;
import com.platinumcoin.pix.payment.domain.port.StatementExportArtifactStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The three in-memory doubles the export worker's plain-Java tests need, in one file because they are
 * only ever used together and each is a handful of lines.
 */
final class FakeStatementExportCollaborators {

    private FakeStatementExportCollaborators() {
    }

    /**
     * An in-memory cold archive keyed exactly as the real one is: by account and month, and — like the
     * real one — <b>streaming</b>. It hands each line to the consumer rather than returning a list,
     * because a double that returned a list would let a buffering implementation pass a test the real
     * adapter's contract forbids.
     */
    static final class FakeArchive implements StatementArchiveReader {

        private final Map<String, List<ArchivedStatementLine>> objects = new LinkedHashMap<>();
        private RuntimeException failure;
        private YearMonth failOnMonth;
        private final List<String> readKeys = new ArrayList<>();

        @Override
        public int stream(String accountId, YearMonth month, Consumer<ArchivedStatementLine> onLine) {
            readKeys.add(key(accountId, month));
            if (failure != null && (failOnMonth == null || failOnMonth.equals(month))) {
                throw failure;
            }
            // A month with no object streams nothing — the "skipped, not failed" rule.
            List<ArchivedStatementLine> lines = objects.getOrDefault(key(accountId, month), List.of());
            lines.forEach(onLine);
            return lines.size();
        }

        void seed(String accountId, YearMonth month, ArchivedStatementLine... lines) {
            objects.put(key(accountId, month), List.of(lines));
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
            this.failOnMonth = null;
        }

        /** Fail only when the worker reaches this month — i.e. after it has already written rows. */
        void failOnMonth(YearMonth month, RuntimeException failure) {
            this.failure = failure;
            this.failOnMonth = month;
        }

        void stopFailing() {
            this.failure = null;
            this.failOnMonth = null;
        }

        List<String> readKeys() {
            return List.copyOf(readKeys);
        }

        private static String key(String accountId, YearMonth month) {
            return "account=" + accountId + "/" + month + ".jsonl";
        }
    }

    /**
     * An in-memory artifact store with the real one's <b>sink</b> shape: content arrives incrementally
     * and the object only exists once {@code finish()} runs. The append/finish/abort counters are what
     * let a test assert the streaming contract — that the worker does not hand over one finished
     * document — and that a failure mid-stream leaves nothing behind.
     */
    static final class FakeArtifactStore implements StatementExportArtifactStore {

        private final Map<String, byte[]> objects = new LinkedHashMap<>();
        private int writes;
        private int presigns;
        private int appends;
        private int finishes;
        private int aborts;

        @Override
        public Sink open(String accountId, String exportId) {
            String key = "exports/" + accountId + "/" + exportId + ".csv";
            return new Sink() {
                private final StringBuilder content = new StringBuilder();
                private boolean finished;

                @Override
                public void append(String text) {
                    appends++;
                    content.append(text);
                }

                @Override
                public String finish() {
                    finishes++;
                    writes++;
                    finished = true;
                    objects.put(key, content.toString().getBytes(StandardCharsets.UTF_8));
                    return key;
                }

                @Override
                public void close() {
                    if (!finished) {
                        aborts++;
                    }
                }
            };
        }

        @Override
        public DownloadLink presign(String objectKey) {
            presigns++;
            return new DownloadLink(
                    "https://example.invalid/" + objectKey + "?signed",
                    Instant.parse("2026-08-29T13:00:00Z").plus(Duration.ofHours(1)));
        }

        byte[] objectAt(String key) {
            return objects.get(key);
        }

        /** How many objects exist — the assertion that a redelivery did not produce a second artifact. */
        int objectCount() {
            return objects.size();
        }

        /** How many times an object was written — one export must not cost two uploads on a replay. */
        int writes() {
            return writes;
        }

        /** How many links were minted — zero is what an unauthorized read must cost. */
        int presignCount() {
            return presigns;
        }

        /** Appends received — one per header/row, never one for a whole finished document. */
        int appendCount() {
            return appends;
        }

        int finishCount() {
            return finishes;
        }

        /** Sinks closed without finishing — an aborted artifact leaves no object. */
        int abortCount() {
            return aborts;
        }
    }

    /** The dedup gate, with the claim/release semantics the real store has. */
    static final class FakeProcessedEvents implements ProcessedEvents {

        private final Set<String> claimed = ConcurrentHashMap.newKeySet();

        @Override
        public boolean claim(String eventId) {
            return claimed.add(eventId);
        }

        @Override
        public void release(String eventId) {
            claimed.remove(eventId);
        }

        boolean isClaimed(String eventId) {
            return claimed.contains(eventId);
        }
    }

    /** A convenience builder so a test's fixture reads as a statement rather than as eight arguments. */
    static ArchivedStatementLine line(
            String accountId, String txId, Direction direction, long cents, Instant when) {
        return new ArchivedStatementLine(
                accountId, txId, direction, cents, "acc-counterpart", when, "PIX", "archived leg");
    }
}
