package com.platinumcoin.pix.payment.infra.client;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.security.OnBehalfOf;
import com.platinumcoin.pix.common.security.ServiceTokenIssuer;
import com.platinumcoin.pix.payment.domain.exception.AccountLookupException;
import com.platinumcoin.pix.payment.domain.port.AccountLimitClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The only place HTTP touches account config (ADR-0010). Implements {@link AccountLimitClient} by
 * calling account-service's {@code GET /internal/accounts/{id}} (ADR-0006: services read each other's
 * config over HTTP, never by sharing {@code pix_accounts}). The internal endpoint already exposes
 * {@code dailyLimitCents} as an integer, so no decimal parsing is needed.
 *
 * <p><b>Service-to-service auth (step 68, ADR-0017).</b> That endpoint sits behind the shared JWT
 * filter, and this client mints its <b>own</b> short-lived token for it — addressed to
 * {@code account-service}, scoped to {@code accounts:read} alone — via the shared {@link ServiceTokenIssuer}.
 * It used to forward the in-flight caller's bearer instead, which is what made every user's login a
 * credential on this platform's internal ports. The user is still named on the call, but only as
 * <i>evidence</i>: {@code X-PlatinumCoin-On-Behalf-Of} carries their id for the logs and the audit
 * trail and is read by no authorization decision anywhere ({@link OnBehalfOf}).
 *
 * <p>The correlation id is propagated automatically by common-lib's {@code RestClient} customizer, so
 * one {@code grep} still stitches the two services' logs together.
 */
@Component
public class HttpAccountLimitClient implements AccountLimitClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAccountLimitClient.class);

    private final RestClient restClient;
    private final ServiceTokenIssuer serviceTokens;

    /** Service-to-service view of an account; only {@code dailyLimitCents} is needed here. */
    record AccountView(long dailyLimitCents) {
    }

    public HttpAccountLimitClient(
            RestClient.Builder builder,
            @Value("${services.account-service.base-url}") String baseUrl,
            ServiceTokenIssuer serviceTokens) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.serviceTokens = serviceTokens;
    }

    @Override
    public long dailyLimitCents(String accountId) {
        log.debug("Reading dailyLimitCents from account-service | accountId={}", accountId);
        try {
            AccountView view = restClient.get()
                    .uri("/internal/accounts/{id}", accountId)
                    .headers(h -> serviceTokens.authorize(h, InternalApi.AUD_ACCOUNT,
                            InternalApi.SCOPE_ACCOUNTS_READ))
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
}
