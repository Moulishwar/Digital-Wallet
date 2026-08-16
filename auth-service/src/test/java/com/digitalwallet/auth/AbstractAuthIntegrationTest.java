package com.digitalwallet.auth;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for auth-service integration tests against a real PostgreSQL.
 *
 * <p>Signing keys are ephemeral here. Tests care that a token verifies against the key the service
 * is currently using, not that it survives a restart, so generating a throwaway keypair avoids
 * committing a real one to the repository purely to make tests run.
 */
@SpringBootTest
public abstract class AbstractAuthIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.allow-ephemeral-keys", () -> true);
        // wallet-service is not running during these tests. That is deliberate: registration is
        // supposed to succeed anyway, and these tests prove it.
        registry.add("wallet.base-url", () -> "http://localhost:59999");
        registry.add("wallet.connect-timeout", () -> "200ms");
        registry.add("wallet.read-timeout", () -> "200ms");
        registry.add("security.service-credential", () -> "test-service-credential");
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetUsers() {
        jdbcTemplate.execute("DELETE FROM refresh_token");
        jdbcTemplate.execute("DELETE FROM user_role");
        jdbcTemplate.execute("DELETE FROM app_user");
    }
}
