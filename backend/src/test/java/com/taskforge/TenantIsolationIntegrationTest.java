package com.taskforge;

import com.taskforge.project.entity.Project;
import com.taskforge.project.repository.ProjectRepository;
import com.taskforge.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantIsolationIntegrationTest — Phase 1 Goal 7
 *
 * <p>This is the critical proof that Row-Level Security works end-to-end.
 * It answers the interview question: <em>"How do you know tenant A can't see
 * tenant B's data?"</em>
 *
 * <p><b>Test strategy:</b>
 * <ol>
 *   <li>Insert tenants and users using raw {@code JdbcTemplate} (no RLS on those tables).</li>
 *   <li>Insert projects inside a transaction with {@code SET LOCAL app.current_tenant_id}
 *       set to the project's owner tenant — required because the FORCE RLS policy's
 *       WITH CHECK clause applies even to the table owner.</li>
 *   <li>Query ALL projects via {@code ProjectRepository.findAll()} using a DIFFERENT
 *       tenant context — no WHERE clause in application code, deliberately.</li>
 *   <li>Assert zero rows returned — the database's RLS policy enforces isolation.</li>
 *   <li>Switch to the correct tenant — assert the project IS visible.</li>
 * </ol>
 *
 * <p><b>Why this matters in interviews:</b>
 * "I wrote an integration test that proves RLS works by querying across tenant
 * boundaries with NO application-level filter — the WHERE clause is never written
 * by the app — and zero rows are returned. That's the database enforcing isolation."
 *
 * <p><b>RLS behaviour with FORCE ROW LEVEL SECURITY:</b>
 * PostgreSQL's {@code FORCE ROW LEVEL SECURITY} (V7 migration) applies policies
 * even to the table owner. Our test datasource connects as the {@code taskforge}
 * user (the table owner). Without {@code FORCE}, the owner would bypass RLS and
 * the test would give a false positive. With {@code FORCE}, the policy applies
 * unconditionally — which is what we verify here.
 *
 * <p><b>NOTE — SET LOCAL scope:</b>
 * {@code SET LOCAL} scopes the variable to the current transaction only. All
 * project INSERTs and SELECTs in this test are wrapped in {@code transactionTemplate}
 * to ensure the variable is active for the duration of each operation.
 */
@DisplayName("RLS Tenant Isolation Integration Tests")
class TenantIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // ──────────────────────────────────────────────────────────────────────────
    // Test helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Seeds a tenant (no RLS on the tenants table) and returns its UUID.
     */
    private UUID seedTenant(String name, String slug) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)", id, name, slug);
        return id;
    }

    /**
     * Seeds a user (no RLS on the users table) and returns their UUID.
     */
    private UUID seedUser(String email, String fullName) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                id, email, "$2a$10$placeholder", fullName);
        return id;
    }

    /**
     * Inserts a project row inside a transaction scoped to {@code tenantId}.
     *
     * <p>WHY: The RLS policy's WITH CHECK expression (derived from the USING clause
     * in V7) requires {@code tenant_id = current_setting('app.current_tenant_id')::uuid}
     * even for INSERT. This method wraps the INSERT in a transaction and sets
     * {@code SET LOCAL app.current_tenant_id = tenantId} so the INSERT is allowed.
     *
     * <p>The table owner ({@code taskforge}) is still subject to FORCE RLS.
     */
    private UUID seedProject(UUID tenantId, UUID createdBy, String name) {
        UUID projectId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL ROLE taskforge_app");
            jdbcTemplate.execute("SET LOCAL app.current_tenant_id = '" + tenantId + "'");
            jdbcTemplate.update(
                    "INSERT INTO projects (id, tenant_id, name, created_by) VALUES (?, ?, ?, ?)",
                    projectId, tenantId, name, createdBy);
            return null;
        });
        return projectId;
    }

    /**
     * Reads all projects visible to {@code tenantId} via a fresh transaction.
     * Uses no application-level WHERE clause — RLS provides the isolation.
     */
    private List<Project> findAllAs(UUID tenantId) {
        return transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL ROLE taskforge_app");
            jdbcTemplate.execute("SET LOCAL app.current_tenant_id = '" + tenantId + "'");
            return projectRepository.findAll();  // ← no WHERE clause — RLS filters rows
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 1 — Cross-tenant read isolation (core proof)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Scenario 1: Tenant B cannot see Tenant A's projects via findAll() — zero rows returned")
    void tenantB_cannotSee_tenantA_projects_via_findAll() {
        // ── SETUP ────────────────────────────────────────────────────────────
        UUID tenantAId = seedTenant("Tenant Alpha Corp", "tenant-alpha");
        UUID tenantBId = seedTenant("Tenant Beta Inc",  "tenant-beta");
        UUID userId    = seedUser("alice@alpha.com", "Alice Alpha");

        seedProject(tenantAId, userId, "Alpha's Secret Project");

        // ── ACT (as Tenant B — a different tenant with no projects) ──────────
        // Even though ProjectRepository.findAll() has NO tenant_id filter in
        // the application code, the RLS policy must return zero rows.
        TenantContextHolder.setTenantId(tenantBId);
        List<Project> tenantBProjects = findAllAs(tenantBId);
        TenantContextHolder.clear();

        // ── ASSERT: RLS blocks cross-tenant read ─────────────────────────────
        // This is the "money assertion". If RLS is broken, tenantBProjects would
        // contain Tenant A's project and the test would fail.
        assertThat(tenantBProjects)
                .as("Tenant B must see ZERO projects — RLS must block cross-tenant reads")
                .isEmpty();

        // ── VERIFY: Tenant A can still see its own project ───────────────────
        List<Project> tenantAProjects = findAllAs(tenantAId);

        assertThat(tenantAProjects)
                .as("Tenant A must see exactly 1 project — its own")
                .hasSize(1);

        assertThat(tenantAProjects.get(0).getName())
                .as("The project returned must be Tenant A's project")
                .isEqualTo("Alpha's Secret Project");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 2 — Non-matching tenant context → empty result (fail-safe)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Scenario 2: Non-existent tenant context → findAll() returns zero rows (fail-safe, not an error)")
    void nonMatchingTenantContext_returns_emptyResult_not_allData() {
        // ── SETUP ────────────────────────────────────────────────────────────
        UUID tenantId = seedTenant("Some Tenant", "some-tenant");
        UUID userId   = seedUser("bob@some.com", "Bob");
        seedProject(tenantId, userId, "Bob's Project");

        // ── ACT: query with a UUID that doesn't match any real tenant ─────────
        // This simulates a bug where the wrong/missing tenant context is set.
        // The RLS policy predicate evaluates to FALSE for every row — returning
        // empty. This is the fail-safe: wrong context = zero rows, not all rows.
        UUID nonExistentTenantId = UUID.randomUUID();
        List<Project> projects = findAllAs(nonExistentTenantId);

        // ── ASSERT ────────────────────────────────────────────────────────────
        assertThat(projects)
                .as("With a non-matching tenant context, RLS must return zero rows — not all tenant data")
                .isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 3 — Multi-tenant data → each tenant sees only its own rows
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Scenario 3: Three tenants, each with N projects — findAll() returns only the current tenant's projects")
    void multipleTenantsInDb_eachSees_onlyOwnProjects() {
        // ── SETUP: seed 3 tenants with 1, 2, and 3 projects respectively ──────
        UUID tenantAId = seedTenant("Alpha",  "alpha-3t");
        UUID tenantBId = seedTenant("Beta",   "beta-3t");
        UUID tenantCId = seedTenant("Gamma",  "gamma-3t");
        UUID userId    = seedUser("shared@test.com", "Shared User");

        // Tenant A: 1 project
        seedProject(tenantAId, userId, "Alpha Project 1");

        // Tenant B: 2 projects
        seedProject(tenantBId, userId, "Beta Project 1");
        seedProject(tenantBId, userId, "Beta Project 2");

        // Tenant C: 3 projects
        seedProject(tenantCId, userId, "Gamma Project 1");
        seedProject(tenantCId, userId, "Gamma Project 2");
        seedProject(tenantCId, userId, "Gamma Project 3");

        // ── ASSERT: each tenant sees only its own projects ────────────────────
        List<Project> alphaProjects = findAllAs(tenantAId);
        assertThat(alphaProjects)
                .as("Tenant A must see exactly 1 project")
                .hasSize(1);
        assertThat(alphaProjects).extracting(Project::getName)
                .containsExactlyInAnyOrder("Alpha Project 1");

        List<Project> betaProjects = findAllAs(tenantBId);
        assertThat(betaProjects)
                .as("Tenant B must see exactly 2 projects")
                .hasSize(2);
        assertThat(betaProjects).extracting(Project::getName)
                .containsExactlyInAnyOrder("Beta Project 1", "Beta Project 2");

        List<Project> gammaProjects = findAllAs(tenantCId);
        assertThat(gammaProjects)
                .as("Tenant C must see exactly 3 projects")
                .hasSize(3);
        assertThat(gammaProjects).extracting(Project::getName)
                .containsExactlyInAnyOrder("Gamma Project 1", "Gamma Project 2", "Gamma Project 3");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 4 — Tenant context switch mid-sequence
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Scenario 4: Switching tenant context mid-sequence — each query returns only the active tenant's data")
    void contextSwitch_midSequence_isolatesCorrectly() {
        // ── SETUP ────────────────────────────────────────────────────────────
        UUID tenantAId = seedTenant("Switch Alpha", "switch-alpha");
        UUID tenantBId = seedTenant("Switch Beta",  "switch-beta");
        UUID userId    = seedUser("switcher@test.com", "Context Switcher");

        seedProject(tenantAId, userId, "Alpha Switch Project");
        seedProject(tenantBId, userId, "Beta Switch Project");

        // ── Round 1: Query as Tenant A ────────────────────────────────────────
        List<Project> round1 = findAllAs(tenantAId);
        assertThat(round1).hasSize(1);
        assertThat(round1.get(0).getName()).isEqualTo("Alpha Switch Project");

        // ── Round 2: Query as Tenant B ────────────────────────────────────────
        List<Project> round2 = findAllAs(tenantBId);
        assertThat(round2).hasSize(1);
        assertThat(round2.get(0).getName()).isEqualTo("Beta Switch Project");

        // ── Round 3: Switch back to Tenant A — must still see only A's data ───
        List<Project> round3 = findAllAs(tenantAId);
        assertThat(round3)
                .as("After switching back to Tenant A, must see Tenant A's project again")
                .hasSize(1);
        assertThat(round3.get(0).getName()).isEqualTo("Alpha Switch Project");
    }
}
