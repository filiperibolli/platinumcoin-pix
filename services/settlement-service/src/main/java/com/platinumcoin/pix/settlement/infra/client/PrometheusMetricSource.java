package com.platinumcoin.pix.settlement.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.platinumcoin.pix.settlement.domain.port.MetricSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The only place the watchdog talks to Prometheus (ADR-0010): {@code POST /api/v1/query} against the
 * container that scrapes every service, turning one PromQL expression into one number.
 *
 * <h2>Timeouts are short on purpose — the opposite posture to the settlement client</h2>
 * {@code HttpSpiSettlementClient} waits 12s because a payment is at stake and giving up early is what
 * costs. Nothing is at stake here: this is a monitoring read on a scheduled tick, and the worst outcome
 * of a slow Prometheus is that one watchdog round is skipped and the next one, seconds later, succeeds.
 * A generous timeout would instead let a wedged monitoring stack hold the scheduler thread — turning an
 * observability problem into an availability one, which is precisely backwards for a component whose
 * entire job is to watch.
 *
 * <h2>Every failure is the same answer: "I do not know"</h2>
 * Prometheus down, query rejected, no series matched, an unparseable body — all become
 * {@link Optional#empty()}. The evaluator's contract is built on this: a rule with no sample is skipped
 * and keeps its remembered state, so the monitoring layer can never fabricate an incident or silently
 * close one. Nothing here throws, because a throwing monitoring read on the scheduler is noise with no
 * recovery path.
 *
 * <h2>POST with a form body, not GET with a query string — and this one is not a style preference</h2>
 * PromQL is written in exactly the characters a URL treats as structure. Two of them bite immediately:
 * <ul>
 *   <li>{@code {stage="DEBITED"}} — braces are URI-template syntax to Spring's {@code RestClient}, so a
 *       plain {@code uri(string)} tries to expand them as variables and mangles the expression.</li>
 *   <li>{@code sum(a) + sum(b)} — {@code +} is legal <b>unencoded</b> in a URL query string, and Go's
 *       form parser (which is what Prometheus uses) decodes it as a <b>space</b>. The addition silently
 *       becomes a syntax error, and only for the rules that happen to add two series.</li>
 * </ul>
 * Both were observed against a live Prometheus before this was written, and both failed the same
 * unhelpful way: {@code 400 bad_data}. Submitting the expression as an
 * {@code application/x-www-form-urlencoded} body sidesteps the entire category — {@link URLEncoder}
 * escapes {@code +} as {@code %2B}, nothing performs template expansion, and the expression Prometheus
 * parses is byte-for-byte the one written in the rule. It is also what Grafana's own datasource does
 * ({@code httpMethod: POST} in the provisioning file), and what makes long expressions immune to URL
 * length limits.
 *
 * <h2>The response shape</h2>
 * A successful instant query returns
 * {@code {"status":"success","data":{"resultType":"vector","result":[{"metric":{…},"value":[<ts>,"<v>"]}]}}}.
 * The value is a two-element array of <i>mixed</i> types — a float timestamp and a <b>string</b> sample —
 * which is why this reads a {@link JsonNode} rather than binding a record: modelling a heterogeneous
 * JSON tuple as a Java type costs more than it explains. An empty {@code result} array is a legitimate
 * success meaning "no series matched" (a counter nobody has incremented on a fresh stack), and is
 * likewise reported as no answer rather than as zero.
 */
@Component
public class PrometheusMetricSource implements MetricSource {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricSource.class);

    /** Generous enough for a local container under load, tight enough never to stall a scheduled tick. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient http;
    private final String baseUrl;

    public PrometheusMetricSource(@Value("${pix.settlement.alerts.prometheus-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).baseUrl(baseUrl).build();
        log.info("Alert watchdog metric source ready, it will read platform-wide metrics from Prometheus "
                        + "so cross-service rules can be evaluated in one place | prometheusUrl={} "
                        + "connectTimeout={} readTimeout={}",
                baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    @Override
    public Optional<Double> instant(String query) {
        try {
            JsonNode body = http.post()
                    .uri("/api/v1/query")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("query=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
                    .retrieve()
                    .body(JsonNode.class);
            return firstSampleOf(body, query);
        } catch (RuntimeException e) {
            // Includes the read timeout, a connection refusal and a 4xx/5xx from Prometheus. Logged at
            // WARN because a blind watchdog is a degradation worth seeing, not an actionable error — the
            // next tick retries and the rules stayed untouched meanwhile.
            log.warn("Could not read a metric from Prometheus, the alert rules that need it are skipped "
                            + "this round rather than guessed | prometheusUrl={} query={} error={}",
                    baseUrl, query, e.toString());
            return Optional.empty();
        }
    }

    private Optional<Double> firstSampleOf(JsonNode body, String query) {
        if (body == null || !"success".equals(body.path("status").asText())) {
            log.warn("Prometheus answered but not with a successful instant query, skipping the rules "
                    + "that need it | query={} status={}", query, body == null ? null : body.path("status").asText());
            return Optional.empty();
        }
        JsonNode result = body.path("data").path("result");
        if (!result.isArray() || result.isEmpty()) {
            log.debug("Prometheus matched no series for this query — normal on a cold stack before the "
                    + "first payment | query={}", query);
            return Optional.empty();
        }
        // value = [<unix ts as float>, "<sample as string>"]; the sample is index 1 and is a string.
        JsonNode value = result.get(0).path("value");
        if (!value.isArray() || value.size() < 2) {
            log.warn("Prometheus returned a result in a shape this adapter does not understand, skipping "
                    + "| query={} value={}", query, value);
            return Optional.empty();
        }
        try {
            double sample = Double.parseDouble(value.get(1).asText());
            log.debug("Sampled a metric for the alert watchdog | query={} value={}", query, sample);
            return Double.isNaN(sample) ? Optional.empty() : Optional.of(sample);
        } catch (NumberFormatException e) {
            log.warn("Prometheus returned a non-numeric sample, skipping | query={} raw={}",
                    query, value.get(1).asText());
            return Optional.empty();
        }
    }
}
