package com.taskforge;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AbstractIntegrationTest — Base class for all Spring Boot integration tests.
 *
 * <p><b>Why Testcontainers?</b>
 * Testcontainers spins up a real {@code pgvector/pgvector:pg16} Docker container —
 * the same image used in docker-compose.yml — so that our Flyway migrations (including
 * the V7 RLS policies) run in an environment identical to production. An in-memory
 * database (H2) cannot test RLS, SET LOCAL session variables, or pgvector.
 *
 * <p><b>Container lifecycle:</b>
 * The {@code @Container} field is {@code static}, which means Testcontainers starts
 * the container ONCE for the entire test class (JUnit 5 with {@code @Testcontainers}).
 * This is the recommended pattern for Spring Boot tests — starting a new container per
 * test method would be prohibitively slow.
 *
 * <p><b>Why @DynamicPropertySource?</b>
 * The container is assigned a random port at startup. {@code @DynamicPropertySource}
 * injects the real JDBC URL into the Spring context before it boots, so
 * {@code spring.datasource.url} points at the container instead of the placeholder.
 *
 * <p><b>Flyway in tests:</b>
 * {@code application-test.yml} enables Flyway. All migrations V1–V7 run when the
 * Spring context starts. This is critical: without V7__enable_rls.sql, the RLS
 * policies don't exist and the isolation test would give a false positive.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    /**
     * Shared PostgreSQL 16 + pgvector container.
     *
     * <p>Static = shared across all test methods in the class.
     * Uses the same image tag as docker-compose.yml to guarantee behaviour parity.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("taskforge_test")
                    .withUsername("taskforge")
                    .withPassword("taskforge");

    /**
     * Injects the container's runtime JDBC URL (with dynamic port) into the
     * Spring context before it initialises the DataSource.
     */
    @DynamicPropertySource
    static void overrideDataSourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Cleans tenant-scoped test data before each test without disrupting
     * the Flyway schema history or global reference data.
     *
     * <p>We truncate in FK-safe order and use CASCADE to handle dependent rows.
     * RESTART IDENTITY is not used because we have UUID PKs (no sequences to reset).
     *
     * <p><b>Why not @Transactional on the test?</b>
     * We deliberately avoid wrapping each test in a rolled-back transaction
     * because {@code SET LOCAL} scopes the session variable to the current
     * transaction. If the entire test ran in one transaction, switching tenant
     * context mid-test would require a save-point, which complicates the test
     * significantly. Instead, we run real commits and clean up between tests.
     */
    @BeforeEach
    void cleanDatabase() {
        // ── Create a non-superuser role for RLS-tested queries ──────────────
        // Testcontainers creates 'taskforge' as the PostgreSQL bootstrap SUPERUSER
        // (via POSTGRES_USER). Superusers bypass ALL RLS policies — even with
        // FORCE ROW LEVEL SECURITY. PostgreSQL 16+ also prevents stripping
        // superuser from the bootstrap user (ERROR: permission denied to alter role).
        //
        // Solution: create a separate non-superuser role 'taskforge_app' that
        // tests switch into via SET LOCAL ROLE inside transactional queries.
        // This role is NOT a superuser and NOT the table owner, so standard RLS
        // policies apply to it unconditionally.
        //
        // Flyway and TRUNCATE still run as 'taskforge' (superuser) — they need it.
        // Only the RLS-tested SELECT/INSERT queries switch to 'taskforge_app'.
        jdbcTemplate.execute(
                "DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'taskforge_app') THEN " +
                "    CREATE ROLE taskforge_app NOSUPERUSER LOGIN PASSWORD 'taskforge'; " +
                "  END IF; " +
                "END $$");
        jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO taskforge_app");
        jdbcTemplate.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO taskforge_app");

        // TRUNCATE is not affected by RLS (it's a DDL-class operation).
        // Runs as 'taskforge' superuser — no role switch needed.
        // Delete in FK-safe order to avoid constraint violations.
        jdbcTemplate.execute("TRUNCATE TABLE task_embeddings CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE notifications CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE api_keys CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE audit_logs CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE usage_records CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE subscriptions CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE task_labels CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE comments CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE tasks CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE labels CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE projects CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE tenant_users CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE refresh_tokens CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE tenants CASCADE");
    }
}
