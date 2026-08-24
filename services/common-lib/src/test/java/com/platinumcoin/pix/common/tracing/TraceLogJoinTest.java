package com.platinumcoin.pix.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.platinumcoin.pix.common.web.CorrelationId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The join between the two observability tools (step 72, ADR-0021 decision 2): the trace id is added to
 * the shared log <b>pattern</b>, next to the ids ADR-0012 already puts there.
 *
 * <h2>Why this test asserts on the pattern and not on a log line</h2>
 * CLAUDE.md forbids asserting on log message prose, and the reason applies with full force here: the
 * promise is not "some class logs the trace id", it is "<b>every</b> record — ours, Spring's, the AWS
 * SDK's — carries it, because it is in the pattern". A test that logged something and grepped the output
 * would prove the weaker statement. So this test asserts the two halves of the actual contract:
 * <ol>
 *   <li>the shared {@code logback-spring.xml} declares a correlation pattern that reads all three MDC
 *       keys, and</li>
 *   <li>rendering a real {@link LoggingEvent} through that pattern with those keys populated prints all
 *       three values — i.e. the MDC key names we depend on are the ones the pattern reads.</li>
 * </ol>
 *
 * <p>The trace id is written into the MDC by Micrometer Tracing's own SLF4J bridge under the key
 * {@code traceId}; the assertion below is what pins that name so a bridge upgrade that renamed it would
 * fail here rather than silently print {@code n/a} in production.
 */
class TraceLogJoinTest {

    /** The shared config every service inherits by depending on common-lib (ADR-0012). */
    private static final Path LOGBACK_CONFIG =
            Path.of("src", "main", "resources", "logback-spring.xml").toAbsolutePath().normalize();

    /** Pulls the {@code defaultValue="…"} out of the LOG_CORRELATION_PATTERN springProperty. */
    private static final Pattern CORRELATION_PATTERN_DEFAULT = Pattern.compile(
            "name=\"LOG_CORRELATION_PATTERN\".*?defaultValue=\"([^\"]+)\"", Pattern.DOTALL);

    @Test
    void correlationPatternReadsCorrelationTransactionAndTraceIds() throws IOException {
        String pattern = sharedCorrelationPattern();

        assertThat(pattern)
                .as("the correlation pattern must read the correlation id (ADR-0012)")
                .contains("%X{" + CorrelationId.MDC_KEY)
                .as("the correlation pattern must read the transaction id (ADR-0012)")
                .contains("%X{" + CorrelationId.TX_ID_MDC_KEY)
                .as("the correlation pattern must read the trace id (ADR-0021 decision 2)")
                .contains("%X{" + CorrelationId.TRACE_ID_MDC_KEY);
    }

    @Test
    void renderingThePatternPrintsAllThreeIds() throws IOException {
        LoggerContext context = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(sharedCorrelationPattern());
        layout.start();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(TraceLogJoinTest.class.getName());
        event.setLevel(Level.INFO);
        event.setMessage("irrelevant — the contract is the pattern, not the sentence");
        event.setMDCPropertyMap(Map.of(
                CorrelationId.MDC_KEY, "cid-42",
                CorrelationId.TX_ID_MDC_KEY, "tx-9f1c",
                CorrelationId.TRACE_ID_MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736"));

        assertThat(layout.doLayout(event))
                .contains("cid-42")
                .contains("tx-9f1c")
                .contains("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    /**
     * A thread that never ran through an HTTP filter and holds no span — a scheduler tick at startup —
     * must still render, printing the placeholder rather than an empty gap. That placeholder is
     * information: it says "this line belongs to no request", which is exactly what it means.
     */
    @Test
    void aThreadWithNoIdsRendersPlaceholdersInsteadOfBlanks() throws IOException {
        LoggerContext context = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(sharedCorrelationPattern());
        layout.start();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(TraceLogJoinTest.class.getName());
        event.setLevel(Level.INFO);
        event.setMessage("startup");
        event.setMDCPropertyMap(Map.of());

        assertThat(layout.doLayout(event)).contains("n/a");
    }

    private static String sharedCorrelationPattern() throws IOException {
        String config = Files.readString(LOGBACK_CONFIG, StandardCharsets.UTF_8);
        Matcher matcher = CORRELATION_PATTERN_DEFAULT.matcher(config);
        assertThat(matcher.find())
                .as("logback-spring.xml must declare LOG_CORRELATION_PATTERN with a default value")
                .isTrue();
        return matcher.group(1);
    }
}
