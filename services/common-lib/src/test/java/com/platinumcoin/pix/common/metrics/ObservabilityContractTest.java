package com.platinumcoin.pix.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>The metric catalog may not drift from the code</b> (step 72).
 *
 * <h2>The failure this exists to make impossible</h2>
 * {@code docs/observability.md} is the catalog an operator reads and the source every alert rule and
 * dashboard panel was written against. Nothing has ever forced it to agree with the meters the platform
 * actually registers, and the two drift in the direction that hurts most: a metric gets renamed in code,
 * the doc keeps the old name, and the panel built from the doc shows a flat zero line — which reads
 * exactly like "nothing is wrong".
 *
 * <p>So this test reads the doc, extracts every {@code pix_*} Prometheus series it names, converts each
 * back to the Micrometer dotted form, and requires that name to appear in the platform's source. A
 * renamed meter fails here until the catalog is updated in the same change — the CLAUDE.md rule that docs
 * and code must not drift, enforced rather than remembered.
 *
 * <h2>What it deliberately does not do</h2>
 * It does not stand up eight Spring contexts to scrape eight {@code /actuator/prometheus} endpoints. That
 * would be a slower test with a weaker guarantee: a meter is only registered once the code path that owns
 * it has run, so an empty registry would prove nothing about a metric that is real but idle. Matching
 * against the source is exact for the property in question — <i>does the platform still spell this metric
 * the way the catalog says?</i>
 */
class ObservabilityContractTest {

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path CATALOG = REPO_ROOT.resolve("docs/observability.md");
    private static final Path SERVICES = REPO_ROOT.resolve("services");

    /** Prometheus series names the catalog mentions: {@code pix_something_something}. */
    private static final Pattern SERIES = Pattern.compile("\\bpix_[a-z0-9_]+\\b");

    /**
     * Suffixes the Prometheus registry appends rather than the code declaring them, stripped before
     * comparing with a meter name. Two things end up here: the <b>type</b> suffix ({@code _total} on a
     * counter) and the meter's <b>base unit</b> ({@code _seconds}, {@code _cents}, {@code _messages}) —
     * {@code Counter.builder("pix.settled.amount").baseUnit("cents")} renders as
     * {@code pix_settled_amount_cents_total}, and neither half of that tail exists in the source.
     * Longest first, so a compound tail is not stripped one piece at a time.
     */
    private static final List<String> PROMETHEUS_SUFFIXES = List.of(
            "_cents_total", "_seconds_total", "_messages_total",
            "_total", "_seconds", "_messages", "_cents");

    /** DynamoDB table names and other {@code pix_*} words in the doc that are not metrics at all. */
    private static final Set<String> NOT_METRICS = Set.of(
            "pix_transactions", "pix_accounts", "pix_keys", "pix_ledger", "pix_idempotency");

    @Test
    void everyMetricTheCatalogNamesIsSpelledThatWayInTheCode() {
        String sources = allPlatformSource();

        for (String series : documentedSeries()) {
            assertThat(sources)
                    .as("docs/observability.md names the series %s, but no service registers a meter "
                            + "spelled %s — the catalog and the registry have drifted, and a dashboard "
                            + "built from the catalog would show a flat zero", series, meterName(series))
                    .contains(meterName(series));
        }
    }

    /** The catalog must actually be naming metrics — a regex that matched nothing would pass vacuously. */
    @Test
    void theCatalogNamesAMeaningfulNumberOfMetrics() {
        assertThat(documentedSeries())
                .as("the extraction found no metrics at all, which means this test is asserting nothing")
                .hasSizeGreaterThanOrEqualTo(8);
    }

    /** The series step 72 adds is in the catalog — the same guard, pointed at this step's own work. */
    @Test
    void theStep72DependencyLatencySeriesIsDocumented() {
        assertThat(documentedSeries())
                .as("pix.dependency.seconds feeds the per-dependency latency panel and must be catalogued")
                .contains("pix_dependency_seconds");
    }

    private static Set<String> documentedSeries() {
        Matcher matcher = SERIES.matcher(read(CATALOG));
        Set<String> series = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group();
            if (!NOT_METRICS.contains(name)) {
                series.add(name);
            }
        }
        return series;
    }

    /**
     * {@code pix_outbox_lag_seconds} → {@code pix.outbox.lag}. Underscores become dots and the
     * registry-appended suffix comes off — the same mapping the Prometheus registry applies in reverse.
     */
    private static String meterName(String series) {
        String base = series;
        for (String suffix : PROMETHEUS_SUFFIXES) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        return base.replace('_', '.').toLowerCase(Locale.ROOT);
    }

    private static String allPlatformSource() {
        try (Stream<Path> files = Files.walk(SERVICES)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/"))
                    .map(ObservabilityContractTest::read)
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
