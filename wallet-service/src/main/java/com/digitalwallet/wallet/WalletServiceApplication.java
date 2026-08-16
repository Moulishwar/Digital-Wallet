package com.digitalwallet.wallet;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WalletServiceApplication {

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
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}
