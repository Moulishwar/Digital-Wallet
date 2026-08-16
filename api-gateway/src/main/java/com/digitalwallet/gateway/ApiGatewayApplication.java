package com.digitalwallet.gateway;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The single public entry point.
 *
 * <p>Reactive, unlike the three services behind it. That is not a stylistic choice: a gateway spends
 * essentially all of its time waiting on other people's I/O, which is exactly the workload a
 * non-blocking stack is for. It also means {@code spring-boot-starter-web} must stay off this
 * module's classpath, since adding it switches the application to the servlet stack and the gateway
 * stops routing.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ApiGatewayApplication {

    static {
        // Consistent with the other services: timestamps in logs and traces should not depend on
        // which machine the process happens to be running on.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
