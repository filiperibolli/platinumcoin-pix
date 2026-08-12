package com.platinumcoin.pix.account.infra.client;

import com.platinumcoin.pix.account.domain.exception.ExternalDirectoryUnavailableException;
import com.platinumcoin.pix.account.domain.model.ExternalDirectoryEntry;
import com.platinumcoin.pix.account.domain.model.PixKeyType;
import com.platinumcoin.pix.account.domain.port.ExternalDirectory;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The only place HTTP touches external key resolution (ADR-0010). Implements {@link ExternalDirectory} by
 * calling mock-bacen's DICT {@code GET /spi/dict/{key}} — the step-30 half of the seam step 11 left marked.
 *
 * <h2>A hard timeout, because this sits on the synchronous send path</h2>
 * Key resolution is the first thing every Pix does and the payer is waiting on it (p99 &lt; 2s for the whole
 * {@code 202}). A hung directory must therefore surface as a timeout in single-digit hundreds of
 * milliseconds, never as a pinned request thread — hence connect + read timeouts (defaults 500ms/1500ms) on
 * a dedicated request factory. mock-bacen deliberately keeps its latency/failure injection <i>off</i> the
 * DICT for the same reason, so in practice this call is fast; the budget exists for the case where the
 * container is gone rather than slow.
 *
 * <h2>404 is an answer; everything else is ignorance</h2>
 * A {@code 404} means no participant holds the key — the one case in which the payer may be told the key
 * does not exist, so it maps to {@code Optional.empty()}. Every other failure (connection refused, read
 * timeout, {@code 5xx}, an unreadable body) maps to {@link ExternalDirectoryUnavailableException} ⇒
 * {@code 503 DIRECTORY_UNAVAILABLE}. <b>Failing closed here is the deliberate opposite of the fraud
 * fail-open (ADR-0005)</b>: there, proceeding unscored carries bounded risk and blocking payments would be
 * worse; here we have no destination to proceed to, so the only decision left is what to tell the caller —
 * and "I could not ask" must not be dressed up as "it does not exist".
 *
 * <p><b>No {@code Authorization} header, on purpose.</b> BACEN is outside PlatinumCoin's trust domain and
 * validates none of our tokens (a real participant presents mTLS + an ICP-Brasil certificate), so this is
 * the one outbound client in the platform that forwards no bearer token. The correlation id still rides
 * along via common-lib's {@code RestClient} customizer, so one {@code grep} spans account-service and the
 * SPI too.
 */
@Component
public class HttpExternalDirectory implements ExternalDirectory {

    private static final Logger log = LoggerFactory.getLogger(HttpExternalDirectory.class);

    private final RestClient restClient;

    /** Wire shape of mock-bacen's {@code DictEntryResponse}. */
    record DictEntryView(String key, String keyType, String ispb, String participant) {
    }

    public HttpExternalDirectory(
            RestClient.Builder builder,
            @Value("${services.bacen.base-url}") String baseUrl,
            @Value("${services.bacen.connect-timeout-ms:500}") long connectTimeoutMs,
            @Value("${services.bacen.read-timeout-ms:1500}") long readTimeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        log.info("External DICT client ready, unknown Pix keys will be delegated to BACEN "
                        + "| baseUrl={} connectTimeoutMs={} readTimeoutMs={}",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    @Override
    public Optional<ExternalDirectoryEntry> lookup(String normalizedKey) {
        log.debug("GET /spi/dict/{} | normalizedValue={}", normalizedKey, normalizedKey);
        DictEntryView view;
        try {
            view = restClient.get()
                    .uri("/spi/dict/{key}", normalizedKey)
                    .retrieve()
                    .body(DictEntryView.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                log.info("BACEN's DICT holds no participant for this key, so it exists nowhere and the "
                        + "caller may be told so | normalizedValue={}", normalizedKey);
                return Optional.empty();
            }
            // A 4xx/5xx that is not a 404 says nothing about whether the key exists.
            throw unavailable(normalizedKey, e);
        } catch (RuntimeException e) {
            // Connection refused, DNS failure, connect/read timeout past the budget.
            throw unavailable(normalizedKey, e);
        }

        if (view == null || !StringUtils.hasText(view.ispb())) {
            // A 2xx we cannot read, or an entry with no ISPB, is not a resolution: without the participant
            // id there is nowhere to route the money. Treated as unavailability rather than not-found —
            // the key may well exist; it is the ANSWER that is unusable.
            log.warn("BACEN's DICT answered without a usable ISPB, treating the directory as unavailable "
                    + "rather than reporting the key as non-existent | normalizedValue={} body={}",
                    normalizedKey, view);
            throw new ExternalDirectoryUnavailableException(
                    "The external Pix key directory returned an unusable answer.", null);
        }

        PixKeyType keyType = parseKeyType(view.keyType(), normalizedKey);
        log.info("BACEN's DICT resolved the key to another participant | normalizedValue={} ispb={} "
                + "participant={} keyType={}", normalizedKey, view.ispb(), view.participant(), keyType);
        return Optional.of(new ExternalDirectoryEntry(view.ispb(), view.participant(), keyType));
    }

    private ExternalDirectoryUnavailableException unavailable(String normalizedKey, RuntimeException cause) {
        // WARN, not ERROR: a dependency being down is a degradation the caller is told to retry, not an
        // actionable fault in this service. The cause is logged here and never returned to the client.
        log.warn("BACEN's DICT could not be consulted, refusing to report the key as non-existent, "
                        + "answering 503 so the caller retries | normalizedValue={} error={}",
                normalizedKey, cause.toString());
        return new ExternalDirectoryUnavailableException(
                "The external Pix key directory is unavailable, try again.", cause);
    }

    /**
     * Map the DICT's key kind onto ours, tolerating a vocabulary we do not share. An unrecognised (or
     * absent) kind degrades to {@code null} instead of failing the resolution: the ISPB is what routes the
     * money, and refusing a payable destination because a foreign registry used a label we never heard of
     * would be a self-inflicted outage.
     */
    private static PixKeyType parseKeyType(String raw, String normalizedKey) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return PixKeyType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("BACEN's DICT reported a key kind PlatinumCoin has no constant for, resolving on the "
                    + "ISPB alone | normalizedValue={} reportedKeyType={}", normalizedKey, raw);
            return null;
        }
    }
}
