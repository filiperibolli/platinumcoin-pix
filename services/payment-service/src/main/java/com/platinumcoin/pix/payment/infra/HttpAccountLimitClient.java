package com.platinumcoin.pix.payment.infra;

import com.platinumcoin.pix.payment.domain.AccountLimitClient;
import com.platinumcoin.pix.payment.domain.AccountLookupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The only place HTTP touches account config (ADR-0010). Implements {@link AccountLimitClient} by
 * calling account-service's {@code GET /internal/accounts/{id}} (ADR-0006: services read each other's
 * config over HTTP, never by sharing {@code pix_accounts}). The internal endpoint already exposes
 * {@code dailyLimitCents} as an integer, so no decimal parsing is needed.
 *
 * <p><b>Service-to-service auth.</b> That endpoint sits behind the shared JWT filter, so this client
 * <b>forwards the caller's bearer token</b> from the in-flight request. That is the local-dev posture
 * (ADR-0007): a deployed build would present a service credential / mTLS scope instead of an end-user
 * token — tracked for step-45 hardening. Reading the current request is an infra concern
 * ({@link RequestContextHolder}), so it never leaks into the domain.
 *
 * <p>The correlation id is propagated automatically by common-lib's {@code RestClient} customizer, so
 * one {@code grep} still stitches the two services' logs together.
 */
@Component
public class HttpAccountLimitClient implements AccountLimitClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAccountLimitClient.class);

    private final RestClient restClient;

    /** Service-to-service view of an account; only {@code dailyLimitCents} is needed here. */
    record AccountView(long dailyLimitCents) {
    }

    public HttpAccountLimitClient(
            RestClient.Builder builder,
            @Value("${services.account-service.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public long dailyLimitCents(String accountId) {
        log.debug("Reading dailyLimitCents from account-service | accountId={}", accountId);
        try {
            AccountView view = restClient.get()
                    .uri("/internal/accounts/{id}", accountId)
                    .headers(this::forwardAuthorization)
                    .retrieve()
                    .body(AccountView.class);
            if (view == null) {
                throw new AccountLookupException(
                        "account-service returned an empty body for account " + accountId, null);
            }
            log.info("Read the debtor's daily limit from account-service | accountId={} dailyLimitCents={}",
                    accountId, view.dailyLimitCents());
            return view.dailyLimitCents();
        } catch (RestClientException e) {
            // Not found, unauthorized, or unreachable — a send cannot proceed without a known limit.
            throw new AccountLookupException(
                    "failed to read dailyLimitCents from account-service for account " + accountId, e);
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
