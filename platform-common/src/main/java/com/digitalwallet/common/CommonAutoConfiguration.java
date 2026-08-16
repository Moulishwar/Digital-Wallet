package com.digitalwallet.common;

import com.digitalwallet.common.error.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Registers the shared beans so services get them by depending on this module, rather than each
 * one having to remember a component-scan path or an {@code @Import}.
 *
 * <p>Wired through {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * — the same mechanism Spring Boot's own starters use. {@code @ConditionalOnMissingBean} means a
 * service can still override the handler with its own if it ever needs to.
 */
@AutoConfiguration
@ConditionalOnClass(DispatcherServlet.class)
public class CommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
