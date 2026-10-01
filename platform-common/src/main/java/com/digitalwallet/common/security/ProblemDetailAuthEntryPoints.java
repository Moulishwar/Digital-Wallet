package com.digitalwallet.common.security;

import com.digitalwallet.common.error.ErrorCode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Makes security rejections look like every other error in the API.
 *
 * <p>Spring Security rejects requests inside the filter chain, before any {@code @ControllerAdvice}
 * can see them, so by default a 401 comes back with an empty body while every other failure returns
 * an RFC 7807 document. Clients then need two ways to read an error. These handlers close that gap.
 *
 * <p>Neither says why authentication failed. "Expired" versus "bad signature" versus "unknown key"
 * is useful to an attacker probing the boundary and useless to a legitimate client, which can only
 * do one thing about any of them: refresh, then log in.
 */
public final class ProblemDetailAuthEntryPoints {

    private ProblemDetailAuthEntryPoints() {
    }

    public static AuthenticationEntryPoint unauthorized(ObjectMapper objectMapper) {
        return (request, response, authException) ->
                write(objectMapper, request, response, ErrorCode.UNAUTHORIZED,
                        "Authentication is required to access this resource");
    }

    public static AccessDeniedHandler forbidden(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) ->
                write(objectMapper, request, response, ErrorCode.FORBIDDEN,
                        "You do not have permission to access this resource");
    }

    private static void write(ObjectMapper objectMapper,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              ErrorCode code,
                              String detail) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
