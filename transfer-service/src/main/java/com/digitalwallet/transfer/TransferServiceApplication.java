package com.digitalwallet.transfer;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Owns transfer <em>intent</em>: what a user asked for, whether it has been carried out, and what
 * to do when the answer is not yet known. It never touches a balance — that is wallet-service's
 * job, and the separation is what keeps the money movement itself a single local transaction.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TransferServiceApplication {

    static {
        // Run in UTC regardless of the host's locale, before anything opens a connection.
        //
        // Two reasons. Practically, the PostgreSQL JDBC driver sends the JVM's default zone to the
        // server on connect, and some platform zone ids — "Asia/Calcutta" on Windows — are legacy
        // aliases that PostgreSQL rejects, so the service would fail to start on one developer's
        // machine and work on another's. More importantly, a ledger's timestamps should not depend
        // on where the process happens to be running.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(TransferServiceApplication.class, args);
    }
}
