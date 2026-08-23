package com.platinumcoin.pix.common.security;

/**
 * The vocabulary of service-to-service identity: <b>who</b> a call is addressed to ({@code aud}) and
 * <b>what</b> it is allowed to do there ({@code scope}) — ADR-0017.
 *
 * <p>These constants exist because the same two strings are written twice, in two languages: by the
 * caller in Java when it mints a token, and by the callee in YAML when it declares which scope a route
 * requires. A typo on either side is not a compile error and not a test failure in the service that
 * made it — it is a {@code 403} in production, at the far end of a network hop, on the money path. So
 * the Java side is pinned here and the YAML side is asserted against it
 * ({@code InternalRouteScopeContractTest} in each service), which is what turns "the strings agree"
 * from a convention into a build failure.
 *
 * <p><b>Audiences are service names, not URLs.</b> The token says which <i>service</i> may accept it,
 * so it stays correct across ports, hostnames and compose networks — the identity is the workload, not
 * the address it happens to be reachable at.
 *
 * <p><b>Scopes are one per internal operation</b>, deliberately narrow: {@code ledger:post} is not
 * accepted for {@code ledger:read} and vice versa. That narrowness is the whole point of the review's
 * acceptance criterion "escopo de serviço é mínimo" — a credential that opens one door is worth far
 * less to an attacker than one that opens every door of the service that issued it.
 */
public final class InternalApi {

    // ---- audiences (aud): the service a token may be presented to -------------------------------

    public static final String AUD_LEDGER = "ledger-service";
    public static final String AUD_ACCOUNT = "account-service";
    public static final String AUD_FRAUD = "fraud-service";

    // ---- scopes: the single operation a token may exercise there --------------------------------

    /** Write a double-entry posting — the platform's only money-moving operation (ADR-0006). */
    public static final String SCOPE_LEDGER_POST = "ledger:post";
    /** Read a balance or a statement page. Never sufficient to move money. */
    public static final String SCOPE_LEDGER_READ = "ledger:read";
    /** Score a transfer in the send path (ADR-0005). */
    public static final String SCOPE_FRAUD_SCORE = "fraud:score";
    /** Read an account's internal view (daily limit, status). */
    public static final String SCOPE_ACCOUNTS_READ = "accounts:read";
    /** Resolve a Pix key to its destination — account-service's DICT role. */
    public static final String SCOPE_KEYS_RESOLVE = "keys:resolve";

    private InternalApi() {
    }
}
