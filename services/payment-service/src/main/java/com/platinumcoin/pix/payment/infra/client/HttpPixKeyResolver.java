package com.platinumcoin.pix.payment.infra.client;

import com.platinumcoin.pix.payment.domain.exception.KeyNotFoundException;
import com.platinumcoin.pix.payment.domain.model.KeyResolution;
import com.platinumcoin.pix.payment.domain.port.PixKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The only place HTTP touches key resolution (ADR-0010). Implements {@link PixKeyResolver} by calling
 * account-service's DICT seam {@code GET /internal/pix-keys/resolve?key=…} (ADR-0006: services read
 * each other's data over HTTP, never by sharing {@code pix_keys}).
 *
 * <p><b>Service-to-service auth.</b> That endpoint sits behind the shared JWT filter, so this client
 * forwards the caller's bearer token from the in-flight request — the local-dev posture (ADR-0007); a
 * deployed build would present a service credential / mTLS scope (step-45 hardening). The correlation
 * id is propagated automatically by common-lib's {@code RestClient} customizer, so one {@code grep}
 * still stitches the two services' logs together.
 *
 * <p><b>Internal or external (step 27).</b> account-service answers with {@code {internal, accountId,
 * externalBank, keyType}} and this adapter passes that verdict through as a {@link KeyResolution} — the
 * send flow, not the adapter, decides what to do with an external destination. Only two answers are
 * refused here: a {@code 404} (the DICT knows no such key) and a malformed <i>internal</i> resolution
 * that names no account, both {@link KeyNotFoundException} ⇒ {@code 422}.
 *
 * <p>account-service can only answer {@code internal=false} once it delegates unknown keys to
 * mock-bacen's DICT (step 30); until then the external branch is unreachable over HTTP, which is why
 * the step-27 tests drive it on the port.
 */
@Component
public class HttpPixKeyResolver implements PixKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(HttpPixKeyResolver.class);

    private final RestClient restClient;

    /** Service-to-service view of a key resolution; mirrors account-service's {@code KeyResolution}. */
    record KeyResolutionView(boolean internal, String accountId, String externalBank, String keyType) {
    }

    public HttpPixKeyResolver(
            RestClient.Builder builder,
            @Value("${services.account-service.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public KeyResolution resolve(String key) {
        log.debug("Resolving a destination Pix key via account-service DICT | keyValue={}", key);
        KeyResolutionView view;
        try {
            view = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/pix-keys/resolve").queryParam("key", key).build())
                    .headers(this::forwardAuthorization)
                    .retrieve()
                    .body(KeyResolutionView.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                log.warn("Destination Pix key did not resolve (account-service 404), returning 422 | "
                        + "keyValue={}", key);
                throw new KeyNotFoundException();
            }
            // Any other HTTP error is not a "key not found" — let it surface (a 401/5xx from the DICT is
            // a dependency failure, not a business decision). Wrapped later if needed; unmapped ⇒ 500.
            throw e;
        }

        if (view == null) {
            log.warn("Destination Pix key resolution returned an empty body, treating the key as "
                    + "unresolvable, returning 422 | keyValue={}", key);
            throw new KeyNotFoundException();
        }

        if (!view.internal()) {
            // The key is held at another PSP: the send debits to the clearing account and settles
            // asynchronously (step 27). Not an error — a different destination.
            log.info("Destination Pix key resolved to another PSP, the send takes the external branch "
                    + "| keyValue={} externalBank={} keyType={}", key, view.externalBank(),
                    view.keyType());
            return KeyResolution.external(view.externalBank());
        }

        if (!StringUtils.hasText(view.accountId())) {
            // An internal resolution that names no account is not payable — a contract violation on the
            // DICT's side, refused here rather than carried into the money-moving path as a null.
            log.warn("Destination Pix key resolved internally but carries no accountId, treating the "
                    + "key as unresolvable, returning 422 | keyValue={} keyType={}", key, view.keyType());
            throw new KeyNotFoundException();
        }

        log.info("Destination Pix key resolved to an internal creditor account | keyValue={} "
                + "creditorAccountId={} keyType={}", key, view.accountId(), view.keyType());
        return KeyResolution.internal(view.accountId());
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
