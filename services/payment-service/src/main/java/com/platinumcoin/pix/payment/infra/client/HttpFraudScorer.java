package com.platinumcoin.pix.payment.infra.client;

import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.port.FraudScorer;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The only place HTTP touches fraud scoring (ADR-0010). Implements {@link FraudScorer} by calling
 * fraud-service's {@code POST /internal/fraud/score} (ADR-0006) with a <b>hard 200ms client budget</b>
 * — connect 50ms + read 150ms — protecting the send SLO from a slow fraud-service.
 *
 * <h2>The fail-open lives here (ADR-0005)</h2>
 * This adapter is the single owner of the project's most debated decision: <b>what the caller does when
 * fraud is slow or down</b>. It is the right home because the fail-<i>open</i> is a reaction to an
 * infrastructure fact — a read/connect timeout or a transport/5xx error — that only the boundary can
 * observe. On any such failure it returns {@link FraudDecision#SKIPPED} rather than throwing, so the
 * payment proceeds unscored and flagged; the residual risk is bounded by daily limits and async
 * re-scoring. A real {@code DENY} is a normal {@code 200} body and flows straight through — "the engine
 * looked and said no" is a business verdict, never a failure. The use case then applies one rule to the
 * four-valued result ({@code DENY} blocks; everything else proceeds), knowing nothing of timeouts.
 *
 * <p><b>Any non-2xx fails open too.</b> The scoring endpoint answers a well-formed request with a
 * {@code 200} carrying the verdict; it has no business 4xx for a scorable send. So a {@code 4xx}/{@code 5xx}
 * here means fraud-service itself is misbehaving (bad deploy, auth drift, overload) — the same
 * availability argument applies, and we skip rather than reject legitimate payments. The wire never
 * carries {@code SKIPPED}; it is minted only on this side.
 *
 * <p><b>Service-to-service auth.</b> The endpoint is behind the shared JWT filter, so the caller's
 * bearer token is forwarded (ADR-0007; a service credential is the deployed posture, step-45). The
 * correlation id is propagated by common-lib's {@code RestClient} customizer, so one {@code grep}
 * stitches the payment and fraud logs together.
 */
@Component
public class HttpFraudScorer implements FraudScorer {

    private static final Logger log = LoggerFactory.getLogger(HttpFraudScorer.class);

    private final RestClient restClient;

    /** Wire shape of the score request — mirrors fraud-service's {@code ScoreRequest}. */
    record ScoreRequest(String accountId, String pixKey, long amountCents, Instant timestamp) {
    }

    /**
     * Just enough of fraud-service's {@code ScoreResult} to read the decision band. The full body also
     * carries {@code score} and {@code reasons[]}, which the in-path caller does not act on (they feed
     * dashboards/analysts, not this branch), so they are deliberately not bound.
     */
    record ScoreResultView(FraudDecision decision) {
    }

    public HttpFraudScorer(
            RestClient.Builder builder,
            @Value("${services.fraud-service.base-url}") String baseUrl,
            @Value("${services.fraud-service.connect-timeout-ms:50}") long connectTimeoutMs,
            @Value("${services.fraud-service.read-timeout-ms:150}") long readTimeoutMs) {
        // connect 50ms + read 150ms = the 200ms hard budget (ADR-0005). A hung fraud-service surfaces as
        // a timeout the catch-all below turns into SKIPPED, never as a pinned request thread.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public FraudDecision score(String accountId, String pixKey, long amountCents, Instant timestamp) {
        ScoreRequest body = new ScoreRequest(accountId, pixKey, amountCents, timestamp);
        log.debug("POST /internal/fraud/score | accountId={} pixKey={} amountCents={} timestamp={}",
                accountId, pixKey, amountCents, timestamp);
        try {
            ScoreResultView result = restClient.post()
                    .uri("/internal/fraud/score")
                    .headers(this::forwardAuthorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ScoreResultView.class);
            if (result == null || result.decision() == null) {
                // A 2xx with an unreadable/empty body is as useless as no answer — fail open.
                log.warn("Fraud-service returned no decision, failing open | accountId={} pixKey={} "
                        + "amountCents={}", accountId, pixKey, amountCents);
                return FraudDecision.SKIPPED;
            }
            log.debug("Fraud-service scored the send | accountId={} pixKey={} decision={}",
                    accountId, pixKey, result.decision());
            return result.decision();
        } catch (RuntimeException e) {
            // The fail-open catch-all (ADR-0005): a connect/read timeout past the budget
            // (ResourceAccessException), an unreachable host, or any 4xx/5xx from fraud-service. Any of
            // these means the check could not complete — proceed unscored, flagged, and let the use case
            // record fraudSkipped=true. We deliberately do NOT rethrow: availability wins at this layer.
            log.warn("Fraud-service call failed (timeout or error), failing open, the send proceeds "
                            + "unscored | accountId={} pixKey={} amountCents={} error={}",
                    accountId, pixKey, amountCents, e.toString());
            return FraudDecision.SKIPPED;
        }
    }

    /** Copy the current request's Authorization header onto the outbound call, if present. */
    private void forwardAuthorization(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String authorization = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (StringUtils.hasText(authorization)) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
    }
}
