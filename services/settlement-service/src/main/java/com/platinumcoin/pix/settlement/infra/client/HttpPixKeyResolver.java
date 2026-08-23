package com.platinumcoin.pix.settlement.infra.client;

import com.platinumcoin.pix.settlement.domain.exception.DirectoryUnavailableException;
import com.platinumcoin.pix.settlement.domain.port.PixKeyResolver;
import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.security.ServiceTokenIssuer;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The only place HTTP touches key resolution from settlement-service (ADR-0010). Implements
 * {@link PixKeyResolver} against account-service's DICT seam
 * {@code GET /internal/pix-keys/resolve?key=…} — the same endpoint payment-service calls on the send path
 * (step 21), reached the same way, because "which account owns this key?" has exactly one answer and one
 * owner (ADR-0006).
 *
 * <p><b>Auth: a self-minted service token.</b> Like the ledger call of step 33, this runs outside any user
 * request — a webhook from BACEN carries no PlatinumCoin token to forward — so the adapter presents a
 * short-lived token from {@link ServiceTokenIssuer}. The correlation id rides along via common-lib's
 * {@code RestClient} customizer, so the inbound credit and the directory lookup it made still grep
 * together.
 *
 * <h2>Translating three HTTP answers into the port's three shapes</h2>
 * <ul>
 *   <li><b>{@code 200 {internal:true, accountId}}</b> ⇒ the account. The one deliverable answer.</li>
 *   <li><b>{@code 404}</b>, or {@code 200 {internal:false}} (the key belongs to another participant), or a
 *       malformed internal resolution naming no account ⇒ {@link Optional#empty()}. For an <i>inbound</i>
 *       payment all three mean the same thing — nobody here can be credited — and the use case bounces it
 *       permanently.</li>
 *   <li><b>Anything else</b> — a {@code 503 DIRECTORY_UNAVAILABLE} (account-service could not reach
 *       BACEN's DICT), any other {@code 5xx}, a timeout, an unreachable host ⇒
 *       {@link DirectoryUnavailableException}. <b>Not</b> an empty answer: "I could not find out" is not
 *       "no", and collapsing them would bounce a deliverable payment because our own dependency blinked.
 *       This is the same distinction account-service itself refuses to blur on the send path (step 30) —
 *       here it matters more, because on this side the mistake destroys someone else's payment.</li>
 * </ul>
 *
 * <p>Short timeouts: the rail is waiting on this call (BACEN holds the webhook open), and a hung directory
 * must surface as a {@code 503} the rail retries rather than pin a request thread.
 */
@Component
public class HttpPixKeyResolver implements PixKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(HttpPixKeyResolver.class);

    private final RestClient restClient;
    private final ServiceTokenIssuer serviceTokens;

    /** Service-to-service view of a key resolution; mirrors account-service's {@code KeyResolution}. */
    record KeyResolutionView(boolean internal, String accountId, String externalBank, String keyType) {
    }

    public HttpPixKeyResolver(
            RestClient.Builder builder,
            ServiceTokenIssuer serviceTokens,
            @Value("${services.account-service.base-url}") String baseUrl,
            @Value("${services.account-service.connect-timeout-ms:500}") long connectTimeoutMs,
            @Value("${services.account-service.read-timeout-ms:1500}") long readTimeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.serviceTokens = serviceTokens;
    }

    @Override
    public Optional<String> resolveToInternalAccount(String keyValue) {
        log.debug("Resolving an inbound Pix key via account-service DICT | keyValue={}", keyValue);

        KeyResolutionView view;
        try {
            view = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/pix-keys/resolve")
                            .queryParam("key", keyValue).build())
                    .headers(h -> serviceTokens.authorize(h, InternalApi.AUD_ACCOUNT,
                            InternalApi.SCOPE_KEYS_RESOLVE))
                    .retrieve()
                    .body(KeyResolutionView.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                log.warn("Inbound Pix key is known to no directory (account-service 404), it is not "
                        + "deliverable here | keyValue={}", keyValue);
                return Optional.empty();
            }
            // Every other status — including the 503 account-service returns when BACEN's DICT is
            // unreachable — leaves the answer UNKNOWN, so the rail must retry rather than be told "no".
            log.warn("Inbound Pix key could not be resolved, the directory answered an unexpected status, "
                            + "treating the answer as unknown so the rail retries | keyValue={} status={}",
                    keyValue, e.getStatusCode().value());
            throw new DirectoryUnavailableException(
                    "key directory answered status " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            log.warn("Key directory unreachable or timed out while resolving an inbound Pix, treating the "
                    + "answer as unknown so the rail retries | keyValue={} error={}", keyValue,
                    e.getMessage());
            throw new DirectoryUnavailableException("key directory unreachable or timed out", e);
        }

        if (view == null) {
            log.warn("Key directory returned an empty body, the key is not deliverable here | keyValue={}",
                    keyValue);
            return Optional.empty();
        }

        if (!view.internal()) {
            // The rail routed this payment to the wrong participant: the key belongs to another PSP.
            log.warn("Inbound Pix key belongs to another participant, so the rail routed this payment to "
                            + "the wrong bank and it is not deliverable here | keyValue={} externalBank={} "
                            + "keyType={}", keyValue, view.externalBank(), view.keyType());
            return Optional.empty();
        }

        if (!StringUtils.hasText(view.accountId())) {
            log.warn("Inbound Pix key resolved internally but names no account, nothing can be credited | "
                    + "keyValue={} keyType={}", keyValue, view.keyType());
            return Optional.empty();
        }

        log.info("Inbound Pix key resolved to a PlatinumCoin account | keyValue={} accountId={} keyType={}",
                keyValue, view.accountId(), view.keyType());
        return Optional.of(view.accountId());
    }
}
