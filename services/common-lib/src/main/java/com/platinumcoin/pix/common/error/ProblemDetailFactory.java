package com.platinumcoin.pix.common.error;

import com.platinumcoin.pix.common.web.CorrelationId;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 7807 {@link ProblemDetail} bodies with the two platform-wide extension members
 * every error must carry: {@code code} (stable, machine-readable) and {@code correlationId}
 * (pulled from the MDC so a client can quote it and we can grep the logs).
 */
public final class ProblemDetailFactory {

    private ProblemDetailFactory() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty("correlationId", MDC.get(CorrelationId.MDC_KEY));
        return problem;
    }
}
