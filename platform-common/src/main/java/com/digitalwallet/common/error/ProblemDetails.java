package com.digitalwallet.common.error;

import java.net.URI;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 7807 body every service returns for a failure.
 *
 * <p>Extracted so there is exactly one definition of that shape. {@link GlobalExceptionHandler}
 * uses it for exceptions on their way out, and transfer-service uses it to build the body it
 * <em>stores</em> against an idempotency key. Those two must be byte-for-byte comparable: if a
 * client's first attempt gets one shape and its retry replays another, the guarantee that a retry
 * returns the original answer is not actually being kept.
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(ErrorCode code, String detail, String instancePath) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        if (instancePath != null) {
            problem.setInstance(URI.create(instancePath));
        }
        problem.setProperty("timestamp", Instant.now().toString());
        attachTraceId(problem);
        return problem;
    }

    /**
     * Adds the current trace id, so a failure a user reports can be found in the logs without
     * guessing which request theirs was.
     */
    public static void attachTraceId(ProblemDetail problem) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
    }
}
