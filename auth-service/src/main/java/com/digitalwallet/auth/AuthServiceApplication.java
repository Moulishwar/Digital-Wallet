package com.digitalwallet.auth;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {

    static {
        // Same reason as wallet-service: the PostgreSQL driver sends the JVM's default zone on
        // connect, and some platform zone ids are legacy aliases the server rejects. Token
        // lifetimes are also easier to reason about when everything is UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
