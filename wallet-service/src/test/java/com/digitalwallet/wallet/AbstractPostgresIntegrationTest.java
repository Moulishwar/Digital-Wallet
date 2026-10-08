package com.digitalwallet.wallet;

import com.digitalwallet.common.security.ServiceCredential;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests, running against a real PostgreSQL 16 in Docker.
 *
 * <p>Deliberately not H2. This ledger depends on behaviour an in-memory database either fakes or
 * lacks outright: {@code SELECT ... FOR UPDATE} semantics, partial unique indexes, and the
 * PL/pgSQL trigger that makes ledger lines append-only. A test suite that passes on H2 and then
 * meets Postgres in production has proven very little.
 *
 * <p>The container is started once for the whole JVM rather than per test class. Postgres takes a
 * couple of seconds to boot, and paying that for every class makes the suite slow enough that
 * people stop running it.
 */
@SpringBootTest
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

    static {
        POSTGRES.start();
    }

    /** The credential /internal/** requires. Tests present it through {@link #serviceCall}. */
    protected static final String TEST_SERVICE_CREDENTIAL = "test-service-credential";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.service-credential", () -> TEST_SERVICE_CREDENTIAL);
    }

    /** Presents the service credential, as auth-service and transfer-service do. */
    protected static RequestPostProcessor serviceCall() {
        return request -> {
            request.addHeader(ServiceCredential.HEADER, TEST_SERVICE_CREDENTIAL);
            return request;
        };
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Returns the database to a clean slate between tests.
     *
     * <p>Note the trigger dance. Ledger lines are append-only and the database enforces it, which
     * means even the test suite cannot simply delete them, exactly as intended. Disabling the
     * trigger for the duration of a truncate is a test-only concession, and the fact that it is
     * needed at all is evidence the protection works.
     *
     * <p>Tests are not wrapped in a rolled-back transaction, because several of them depend on
     * work actually committing: idempotency across separate transactions, and concurrent writers
     * racing for the same unique key.
     */
    @BeforeEach
    void resetLedger() {
        jdbcTemplate.execute("ALTER TABLE ledger_line DISABLE TRIGGER USER");
        try {
            jdbcTemplate.execute("DELETE FROM ledger_line");
        } finally {
            jdbcTemplate.execute("ALTER TABLE ledger_line ENABLE TRIGGER USER");
        }
        jdbcTemplate.execute("DELETE FROM journal_entry");
        jdbcTemplate.execute("DELETE FROM account WHERE owner_user_id IS NOT NULL");
        // System accounts are seeded by migration and must survive; only their balances reset.
        jdbcTemplate.execute("UPDATE account SET balance_minor = 0 WHERE owner_user_id IS NULL");
    }
}
