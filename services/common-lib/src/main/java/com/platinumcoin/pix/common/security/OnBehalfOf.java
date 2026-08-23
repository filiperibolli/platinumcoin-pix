package com.platinumcoin.pix.common.security;

/**
 * The header that carries <b>which human's request</b> caused an internal call:
 * {@code X-PlatinumCoin-On-Behalf-Of: <userId>}.
 *
 * <h2>Evidence, never authorization (ADR-0017 decision 6)</h2>
 * <b>No service reads this header to make an access decision, and none ever may.</b> Authority on an
 * internal port comes from the service token's {@code typ}/{@code aud}/{@code scope} claims and from
 * nothing else; this header is unsigned, trivially forgeable by anyone who can already reach the port,
 * and exists purely so a log line and an audit record can say <i>alice's payment caused this posting</i>
 * instead of trailing off at <i>payment-service posted something</i>.
 *
 * <p>That distinction is the entire lesson of this step restated one layer down. Step 68 removed the
 * user's <i>token</i> from internal calls because a credential that travels for identification ends up
 * being used for authorization. Putting the user's <i>id</i> back on the call re-creates exactly that
 * temptation in a weaker form — so the rule is written here, at the declaration, where the next person
 * to reach for it will read it, and it is enforced by {@code OnBehalfOfNeverAuthorizesTest}, which
 * fails the build if any service's main source ever reads this header back.
 */
public final class OnBehalfOf {

    /** Never consulted in a conditional — see the class javadoc, and the test that enforces it. */
    public static final String HEADER = "X-PlatinumCoin-On-Behalf-Of";

    private OnBehalfOf() {
    }
}
