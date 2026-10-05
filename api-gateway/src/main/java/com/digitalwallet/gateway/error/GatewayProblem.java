package com.digitalwallet.gateway.error;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes the same RFC 7807 body the services behind this gateway return.
 *
 * <p>Reproduced here rather than reused from {@code platform-common}, because that module's handler
 * is a servlet {@code @RestControllerAdvice} taking an {@code HttpServletRequest}, and depending on
 * it would pull the servlet stack onto this application's classpath and stop it being a gateway at
 * all. A caller should not be able to tell from the shape of an error whether it was rejected at the
 * edge or deeper in, so the duplication buys a consistent contract.
 */
public final class GatewayProblem {

    private static final String TYPE_PREFIX = "https://digitalwallet.example/errors/";

    private GatewayProblem() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, ObjectMapper objectMapper,
                                   HttpStatus status, String slug, String title, String detail) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(java.net.URI.create(TYPE_PREFIX + slug));
        problem.setTitle(title);
        problem.setInstance(java.net.URI.create(exchange.getRequest().getPath().value()));
        problem.setProperty("timestamp", Instant.now().toString());

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(problem);
        } catch (Exception e) {
            // Never let failing to describe an error become a different, worse error.
            body = ("{\"title\":\"" + title + "\",\"status\":" + status.value() + "}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
