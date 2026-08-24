package com.platinumcoin.pix.payment.infra.client;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.security.ServiceTokenIssuer;
import com.platinumcoin.pix.common.tracing.ForceSample;
import com.platinumcoin.pix.common.tracing.TracePropagation;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import com.platinumcoin.pix.payment.domain.port.FraudScorer;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
 * <h2>Two failure classes, one outcome (ADR-0018, step 70)</h2>
 * A failure used to be a failure: one {@code catch} turned every one of them into {@code SKIPPED}. But
 * the availability argument above is an argument about <b>capacity</b> — the check ran out of time, the
 * risk is bounded, payments should continue — and it simply does not extend to a {@code 401}, a drifted
 * contract or a bug in this class. There the check is not slow, it is <b>broken</b>: it will not recover
 * when load falls, and every payment goes unscored until a human notices. So this adapter classifies,
 * which it can do precisely because it is the only place the transport fact is visible:
 * <ul>
 *   <li><b>Transient</b> → {@link FraudDecision#SKIPPED}: connect/read timeout, unreachable host,
 *       connection reset, {@code 5xx}, {@code 429}.</li>
 *   <li><b>Non-transient</b> → {@link FraudDecision#FRAUD_ERROR}: {@code 401}/{@code 403}, any other
 *       {@code 4xx}, an unbindable or absent body on a {@code 2xx}, and anything escaping this method's
 *       own logic.</li>
 * </ul>
 * <b>Both still proceed.</b> That is deliberate and is ADR-0005's choice left standing — a bad
 * fraud-service deploy must not become a payments outage, which would turn a detection gap into a revenue
 * incident and make every fraud deploy a money-moving change. What the split buys is not a different
 * behaviour but a different <i>signal</i>: {@code ERROR} instead of {@code WARN}, its own metric series,
 * its own {@code fraud_broken} alert firing on the first occurrence, and a durable stamp on the
 * transaction. A real {@code DENY} remains untouched by all of this — it is a normal {@code 200} body, a
 * business verdict, never a failure. Neither failure value is ever carried on the wire; both are minted
 * here, because only the caller can observe that its own call failed.
 *
 * <p><b>Service-to-service auth (step 68, ADR-0017).</b> This call mints its <b>own</b> short-lived
 * token, addressed to {@code AUD_FRAUD} and scoped to {@code SCOPE_FRAUD_SCORE} alone, via the shared
 * {@link ServiceTokenIssuer} — it used to forward the end user's bearer, which made any user's login
 * a working credential on this platform's internal ports. The user travels as <i>evidence</i> only:
 * their id rides along in {@code X-PlatinumCoin-On-Behalf-Of}, read by logs and the audit trail and
 * by no authorization decision anywhere. The correlation id is propagated by common-lib's
 * {@code RestClient} customizer, so one {@code grep} stitches both services' logs together.
 */
@Component
public class HttpFraudScorer implements FraudScorer {

    private static final Logger log = LoggerFactory.getLogger(HttpFraudScorer.class);

    private final RestClient restClient;
    private final ServiceTokenIssuer serviceTokens;

    /**
     * The tracer, for the one manual span this adapter draws (step 72, ADR-0021 decision 3). Nullable —
     * scoring a payment must never depend on the observability stack.
     */
    private final Tracer tracer;

    /** The total span factory — never throws, may return {@code null} (ADR-0021). */
    private final TracePropagation tracing;

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

    /**
     * Direct construction without tracing, for unit tests and for any caller that already decided this
     * adapter runs untraced. Kept as a separate constructor rather than a nullable parameter so the
     * Spring path stays the one annotated {@code @Autowired} and nothing has to guess.
     */
    public HttpFraudScorer(
            RestClient.Builder builder,
            String baseUrl,
            long connectTimeoutMs,
            long readTimeoutMs,
            ServiceTokenIssuer serviceTokens) {
        this(builder, baseUrl, connectTimeoutMs, readTimeoutMs, serviceTokens,
                (ObjectProvider<Tracer>) null, (ObjectProvider<TracePropagation>) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HttpFraudScorer(
            RestClient.Builder builder,
            @Value("${services.fraud-service.base-url}") String baseUrl,
            @Value("${services.fraud-service.connect-timeout-ms:50}") long connectTimeoutMs,
            @Value("${services.fraud-service.read-timeout-ms:150}") long readTimeoutMs,
            ServiceTokenIssuer serviceTokens,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<TracePropagation> tracing) {
        this.tracer = tracer == null ? null : tracer.getIfAvailable();
        this.tracing = tracing == null ? null : tracing.getIfAvailable();
        this.serviceTokens = serviceTokens;
        // connect 50ms + read 150ms = the 200ms hard budget (ADR-0005). A hung fraud-service surfaces as
        // a ResourceAccessException the transient branch below turns into SKIPPED — never as a pinned
        // request thread, and never confused with fraud-service answering something broken.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public FraudDecision score(String accountId, String pixKey, long amountCents, Instant timestamp) {
        // A manual span, even though the RestClient call underneath is already auto-instrumented, because
        // the two measure different things: the HTTP span measures a request, this one measures THE 200ms
        // BUDGET (ADR-0005) — connect + read + classify + decide. When a send breaches its 2s SLO, "did
        // fraud eat the budget?" is a question about this interval, not about one socket.
        //
        // null covers three cases with one branch — tracing off, no propagation bean, or span creation
        // failed — and all three mean the same thing: score the payment untraced (ADR-0021).
        Span budget = tracing == null ? null : tracing.newSpan("pix.fraud.budget");
        if (budget == null || tracer == null) {
            return doScore(accountId, pixKey, amountCents, timestamp);
        }
        try (Tracer.SpanInScope scope = tracer.withSpan(budget)) {
            FraudDecision decision = doScore(accountId, pixKey, amountCents, timestamp);
            budget.tag("pix.fraud.decision", decision.name());
            return decision;
        } finally {
            budget.end();
        }
    }

    private FraudDecision doScore(String accountId, String pixKey, long amountCents, Instant timestamp) {
        ScoreRequest body = new ScoreRequest(accountId, pixKey, amountCents, timestamp);
        log.debug("POST /internal/fraud/score | accountId={} pixKey={} amountCents={} timestamp={}",
                accountId, pixKey, amountCents, timestamp);
        try {
            ScoreResultView result = restClient.post()
                    .uri("/internal/fraud/score")
                    .headers(h -> serviceTokens.authorize(h, InternalApi.AUD_FRAUD,
                            InternalApi.SCOPE_FRAUD_SCORE))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ScoreResultView.class);
            if (result == null || result.decision() == null) {
                // A 2xx carrying no decision this side can read is a CONTRACT failure, not a slow one: a
                // renamed field in fraud-service's ScoreResult, or a box in front of it answering 200 with
                // something else. Nothing about it improves next minute, and the deploy that caused it
                // looks green — which is exactly why it must not hide in the fail-open bucket (ADR-0018).
                ForceSample.mark("fraud-service answered 2xx with a body this adapter cannot read");
                log.error("Fraud-service answered 2xx with no decision this adapter can read, the "
                                + "contract has drifted, the send proceeds UNSCORED and is flagged "
                                + "FRAUD_ERROR | accountId={} pixKey={} amountCents={} body={}",
                        accountId, pixKey, amountCents, result);
                return FraudDecision.FRAUD_ERROR;
            }
            log.debug("Fraud-service scored the send | accountId={} pixKey={} decision={}",
                    accountId, pixKey, result.decision());
            return result.decision();
        } catch (RestClientResponseException e) {
            // fraud-service answered, with a status. The status is the whole classification.
            String detail = "status=" + e.getStatusCode().value() + " body=" + e.getResponseBodyAsString();
            if (isTransient(e.getStatusCode())) {
                return transientFailure(accountId, pixKey, amountCents,
                        "fraud-service is overloaded or restarting", detail);
            }
            // A 401/403 is a credential that is wrong and stays wrong — after ADR-0017, a service token
            // minted without the fraud:score scope lands exactly here and would otherwise disable fraud
            // screening platform-wide in silence. Any other 4xx is a request fraud-service will not accept
            // however many times we send it.
            return brokenCheck(accountId, pixKey, amountCents,
                    "fraud-service refused the request and will refuse it again", detail);
        } catch (RestClientException e) {
            // No usable answer at all. This is the branch where the classification is NOT the exception
            // type, and getting that wrong is the easiest mistake in this class: RestClient reports a read
            // timeout and an unreadable body through the SAME "Error while extracting response" exception,
            // because both surface while it is trying to turn the response into a ScoreResultView.
            if (isTransportFailure(e)) {
                return transientFailure(accountId, pixKey, amountCents,
                        "the call did not complete inside the 200ms budget or the host is unreachable",
                        e.toString());
            }
            return brokenCheck(accountId, pixKey, amountCents,
                    "fraud-service answered something this adapter cannot read", e.toString());
        } catch (RuntimeException e) {
            // Anything else escaping this adapter — a serialization fault, an NPE in our own logic. A bug
            // in the code that decides whether to screen for fraud, silently answering "don't", is the
            // least defensible thing to report as a capacity blip.
            return brokenCheck(accountId, pixKey, amountCents,
                    "the fraud adapter itself failed", e.toString());
        }
    }

    /**
     * {@code 5xx} and {@code 429} are the two statuses that are statements about <b>capacity</b>: the
     * server is overloaded, restarting, or explicitly asking us to slow down. Load falling away fixes
     * them. Every other status is a statement about the <b>request</b> or the <b>credential</b>, and no
     * amount of waiting fixes those.
     */
    private static boolean isTransient(HttpStatusCode status) {
        return status.is5xxServerError() || status.value() == 429;
    }

    /**
     * Did the network fail to deliver the bytes? That — not the exception class — is what separates the
     * two classes once fraud-service has failed to give us a readable answer.
     *
     * <h2>Why not simply "is there an {@code IOException} in the cause chain?"</h2>
     * Because <b>{@code JsonProcessingException} extends {@code IOException}</b>. A field renamed in
     * fraud-service's {@code ScoreResult}, or a decision band this side has never heard of, arrives as a
     * Jackson failure wrapped in a {@code RestClientException} whose cause chain contains an
     * {@code IOException} — and the tidy-looking {@code instanceof IOException} test would file the single
     * most important contract-drift case under "capacity". So this asks the narrower, honest question and
     * names the three {@code java.net} shapes ADR-0018 enumerates: a read/connect timeout, an unreachable
     * or unknown host, a reset connection. {@link ResourceAccessException} is accepted outright because
     * Spring raises it for I/O errors and nothing else.
     */
    private static boolean isTransportFailure(RestClientException e) {
        if (e instanceof ResourceAccessException) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof SocketException
                    || cause instanceof UnknownHostException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break; // a self-referencing cause chain would otherwise spin forever
            }
        }
        return false;
    }

    /** Fail-open, class one: WARN, {@code SKIPPED}, and the payment proceeds (ADR-0005 unchanged). */
    private static FraudDecision transientFailure(
            String accountId, String pixKey, long amountCents, String why, String detail) {
        // A fail-open is one of the five things ADR-0021 decision 5 always keeps: a payment that went out
        // UNSCORED is precisely the trace an analyst opens, and it is also the rarest, so a head ratio is
        // most likely to have thrown it away.
        ForceSample.mark("the fraud check failed open, this payment went out unscored");
        log.warn("Fraud check could not complete, {}, failing open, the send proceeds unscored and "
                        + "flagged | accountId={} pixKey={} amountCents={} class=TRANSIENT detail={}",
                why, accountId, pixKey, amountCents, detail);
        return FraudDecision.SKIPPED;
    }

    /**
     * Fail-open, class two: ERROR, {@code FRAUD_ERROR}, and the payment <b>still</b> proceeds. The level
     * is the honest one under ADR-0012 — this is actionable, somebody has to go and fix a deploy — and it
     * is the whole delta the classification buys, since the behaviour is identical.
     */
    private static FraudDecision brokenCheck(
            String accountId, String pixKey, long amountCents, String why, String detail) {
        ForceSample.mark("the fraud check is broken (FRAUD_ERROR), the control is off for every payment");
        log.error("Fraud check is BROKEN, not slow — {}; the send still proceeds (ADR-0018 keeps "
                        + "ADR-0005's availability choice) but goes out UNSCORED and is flagged "
                        + "FRAUD_ERROR for async re-scoring | accountId={} pixKey={} amountCents={} "
                        + "class=NON_TRANSIENT detail={}",
                why, accountId, pixKey, amountCents, detail);
        return FraudDecision.FRAUD_ERROR;
    }
}
