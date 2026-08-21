package com.platinumcoin.pix.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import com.platinumcoin.pix.ledger.domain.model.LedgerEntry;
import com.platinumcoin.pix.ledger.domain.model.PostingCommand;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.usecase.ArchiveOutcome;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The statement cold archive end to end (ARCHITECTURE §6.10, step 43): entries that have aged out of
 * the hot window are copied to S3 {@code pix-statement-archive} as {@code account=<id>/yyyy-MM.jsonl} —
 * against the real seeded table and the real bucket.
 *
 * <p><b>The assertion that matters is the one about what did NOT happen.</b> Archiving is a copy: after
 * the run, every entry is still in the ledger and every balance is what it was. That is deliberate (see
 * {@code ArchiveOldEntriesUseCase} for the production difference), and it is the reason this job can
 * exist at all in a codebase whose fifth safety rule is that ledger history is append-only.
 *
 * <p>Its own fixture accounts, like every money-moving IT in this module: all {@code *IT}s share one
 * container and the step-13 tests assert the seeded supply in absolute terms
 * ({@link LedgerAccountFixture}).
 */
@SpringBootTest
class StatementArchiverIT extends LocalStackTestBase {

    private static final String BUCKET = "pix-statement-archive";

    /** Comfortably older than any hot window this test uses, and in two different months. */
    private static final Instant MAY_FIRST = Instant.parse("2026-05-04T10:00:00.000Z");
    private static final Instant MAY_SECOND = Instant.parse("2026-05-19T11:30:00.000Z");
    private static final Instant JUNE = Instant.parse("2026-06-02T08:15:00.000Z");

    private static final S3Client S3 = S3Client.builder()
            .endpointOverride(localstack().getEndpoint())
            .region(Region.of(localstack().getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
            .forcePathStyle(true)
            .build();

    /**
     * A 30-day hot window, so the May/June postings above are cold on any date this suite could run and
     * a posting stamped "now" is unambiguously hot. The production default (90 days) is the same code
     * path with a different number.
     */
    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("pix.archive.hot-window-days", () -> "30");
    }

    @Autowired
    StatementArchiver archiver;

    @Autowired
    LedgerRepository repository;

    @Autowired
    DynamoDbClient dynamo;

    private String payer;
    private String payee;
    private String txPrefix;
    private String recentTxId;

    @BeforeEach
    void postAHistoryAcrossTheWindowBoundary() {
        payer = LedgerAccountFixture.uniqueAccountId("it-arch-payer");
        payee = LedgerAccountFixture.uniqueAccountId("it-arch-payee");
        txPrefix = LedgerAccountFixture.uniqueAccountId("it-arch-tx") + "-";
        LedgerAccountFixture.openAccount(dynamo, payer, 1_000_000L);
        LedgerAccountFixture.openAccount(dynamo, payee, 0L);

        // Three cold postings — two in May, one in June — and one that is still hot.
        post(txPrefix + "may-1", 12_550L, MAY_FIRST);
        post(txPrefix + "may-2", 7_700L, MAY_SECOND);
        post(txPrefix + "jun-1", 3_300L, JUNE);
        recentTxId = txPrefix + "hot";
        post(recentTxId, 5_000L, Instant.now());
    }

    /**
     * The headline: cold entries land in one object per month, with the whole line — account, signed
     * integer cents, counterpart, type and description — and the hot posting stays out of the archive.
     */
    @Test
    void entriesOlderThanTheHotWindowAreArchivedIntoOneObjectPerMonth() {
        ArchiveOutcome outcome = archiver.archiveOnce();

        assertThat(outcome.entriesArchived()).as("this account's three cold legs, at least").isPositive();

        List<String> may = read("account=" + payer + "/2026-05.jsonl");
        assertThat(may).hasSize(2);
        assertThat(may.getFirst())
                .as("oldest first, and the whole line — an archive object reads like a statement")
                .contains("\"accountId\":\"" + payer + "\"")
                .contains("\"txId\":\"" + txPrefix + "may-1\"")
                .contains("\"direction\":\"DEBIT\"")
                // Signed integer cents, never a decimal string: the archive is an internal artefact and
                // this is a five-year record (domain safety rule 6).
                .contains("\"amountCents\":-12550")
                .contains("\"counterpartAccountId\":\"" + payee + "\"")
                .contains("\"description\":\"archived leg\"");
        assertThat(may.get(1)).contains(txPrefix + "may-2");

        assertThat(read("account=" + payer + "/2026-06.jsonl"))
                .hasSize(1).first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains(txPrefix + "jun-1");

        assertThat(String.join("\n", may))
                .as("a posting inside the hot window belongs to the online statement, not the archive")
                .doesNotContain(recentTxId);
        // The credit legs are archived under the payee's own key — an entry belongs to its account.
        assertThat(read("account=" + payee + "/2026-05.jsonl"))
                .hasSize(2).first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("\"direction\":\"CREDIT\"")
                .contains("\"amountCents\":12550");
    }

    /**
     * <b>Nothing left the ledger.</b> Every entry the archive now holds is still online, and both
     * balances are untouched: the local platform deliberately stops short of the delete a production
     * deployment would do after verifying the object.
     */
    @Test
    void hotStorageIsUntouchedBecauseArchivingOnlyEverCopies() {
        long payerBalanceBefore = repository.getBalance(payer).orElseThrow().balanceCents();
        long payeeBalanceBefore = repository.getBalance(payee).orElseThrow().balanceCents();

        archiver.archiveOnce();

        assertThat(repository.getEntries(payer, null, 50).entries())
                .extracting(LedgerEntry::txId)
                .as("all four postings — the three archived ones included — are still in the ledger")
                .containsExactlyInAnyOrder(
                        txPrefix + "may-1", txPrefix + "may-2", txPrefix + "jun-1", recentTxId);
        assertThat(repository.getBalance(payer).orElseThrow().balanceCents()).isEqualTo(payerBalanceBefore);
        assertThat(repository.getBalance(payee).orElseThrow().balanceCents()).isEqualTo(payeeBalanceBefore);
    }

    /**
     * Re-running the job is safe and produces the same archive: the objects are rewritten whole from a
     * ledger the previous run did not change. That is what makes an interrupted run a non-event — you
     * simply run it again.
     */
    @Test
    void aSecondRunRewritesTheSameObjectsRatherThanDuplicatingLines() {
        archiver.archiveOnce();
        List<String> afterFirstRun = read("account=" + payer + "/2026-05.jsonl");

        archiver.archiveOnce();

        assertThat(read("account=" + payer + "/2026-05.jsonl"))
                .as("an overwrite, not an append — the object is a projection of the ledger")
                .isEqualTo(afterFirstRun);
    }

    /** An account whose history is entirely hot gets no object at all — an empty file is not an archive. */
    @Test
    void anAccountWithNothingColdGetsNoObject() {
        String fresh = LedgerAccountFixture.uniqueAccountId("it-arch-fresh");
        LedgerAccountFixture.openAccount(dynamo, fresh, 100_000L);
        repository.post(new PostingCommand(
                txPrefix + "fresh", fresh, payee, 1_000L, "PIX_OUT", "hot leg"), Instant.now());

        archiver.archiveOnce();

        assertThat(objectExists("account=" + fresh + "/"
                + java.time.YearMonth.now(java.time.ZoneOffset.UTC) + ".jsonl")).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void post(String txId, long amountCents, Instant postedAt) {
        repository.post(
                new PostingCommand(txId, payer, payee, amountCents, "PIX_OUT", "archived leg"), postedAt);
    }

    /** The object's lines, read back from the cold archive. */
    private List<String> read(String key) {
        String body = S3.getObjectAsBytes(request -> request.bucket(BUCKET).key(key))
                .asString(StandardCharsets.UTF_8);
        assertThat(body).as("JSONL objects end with a newline").endsWith("\n");
        return List.of(body.substring(0, body.length() - 1).split("\n"));
    }

    private boolean objectExists(String key) {
        try {
            S3.headObject(request -> request.bucket(BUCKET).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
