package com.digitalwallet.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into RFC 7807 Problem Details so every service fails in the same shape.
 *
 * <p>Two rules hold throughout. First, nothing derived from an unexpected exception reaches the
 * client: the stack trace is logged, and the caller gets a fixed message. Leaking a message like
 * "duplicate key value violates unique constraint app_user_email_key" confirms that an email is
 * registered, which is an account-enumeration vector. Second, every response carries the trace id
 * so a user-reported failure can be found in the logs without guessing.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex, HttpServletRequest request) {
        // 5xx codes mean a bug on our side, so they are logged loudly. 4xx are ordinary outcomes.
        if (ex.code().status().is5xxServerError()) {
            log.error("Server-side failure [{}] on {}", ex.code(), request.getRequestURI(), ex);
        } else {
            log.debug("Client failure [{}] on {}: {}", ex.code(), request.getRequestURI(), ex.getMessage());
        }
        return problem(ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid", request);
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return problem(ErrorCode.VALIDATION_FAILED, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        // Spring's own web exceptions (a missing required header, an unparseable body, a bad path
        // variable type) already carry the right status. Without this branch the catch-all below
        // would turn every one of them into a 500, which would be both wrong and confusing to
        // anyone calling the API.
        if (ex instanceof ErrorResponse errorResponse) {
            ProblemDetail body = errorResponse.getBody();
            body.setInstance(URI.create(request.getRequestURI()));
            body.setProperty("timestamp", Instant.now().toString());
            attachTraceId(body);
            log.debug("Web layer rejected request to {}: {}", request.getRequestURI(), ex.getMessage());
            return body;
        }

        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        // Deliberately generic: the real message may describe internal structure.
        return problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", request);
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
        return ProblemDetails.of(code, detail, request.getRequestURI());
    }

    private void attachTraceId(ProblemDetail problem) {
        ProblemDetails.attachTraceId(problem);
    }
}
