package com.platinumcoin.pix.payment.infra;

import com.platinumcoin.pix.payment.domain.KeyNotFoundException;
import com.platinumcoin.pix.payment.domain.PixKeyResolver;
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
 * <p><b>Internal only, this step.</b> account-service answers with {@code {internal, accountId,
 * externalBank, keyType}}. The internal-send flow can only pay an internal creditor, so a resolution
 * that is not internal is treated as {@link KeyNotFoundException} here (external routing lands in step
 * 27/30). A {@code 404} from account-service — an unknown key — is the same {@code KeyNotFoundException}.
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
    public String resolveInternalCreditor(String key) {
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

        if (view == null || !view.internal() || !StringUtils.hasText(view.accountId())) {
            // Resolved, but not to an internal creditor this flow can pay. External keys are step 27/30.
            log.warn("Destination Pix key resolved to a non-internal creditor, out of scope until "
                    + "external settlement (step 27), returning 422 | keyValue={} internal={} "
                    + "externalBank={}", key, view == null ? null : view.internal(),
                    view == null ? null : view.externalBank());
            throw new KeyNotFoundException();
        }

        log.info("Destination Pix key resolved to an internal creditor account | keyValue={} "
                + "creditorAccountId={} keyType={}", key, view.accountId(), view.keyType());
        return view.accountId();
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
