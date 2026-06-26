package com.taskforge.project.repository;

import com.taskforge.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * ProjectRepository — data access for the {@link Project} entity.
 *
 * <p><b>RLS does the tenant filtering:</b>
 * Every query here is automatically scoped to the current tenant because:
 * <ol>
 *   <li>{@link com.taskforge.tenant.TenantFilter} puts the tenant ID in
 *       {@link com.taskforge.tenant.TenantContextHolder}.</li>
 *   <li>{@link com.taskforge.tenant.TenantConnectionInterceptor} runs
 *       {@code SET LOCAL app.current_tenant_id = ?} before SQL executes.</li>
 *   <li>PostgreSQL's RLS policy on {@code projects} filters rows by that session variable.</li>
 * </ol>
 * This means {@code findAll()} here returns only the current tenant's projects —
 * with zero application-level WHERE clauses needed. This is proven by Goal 7's
 * integration test.
 *
 * <p><b>Soft-delete is transparent:</b>
 * {@link Project} carries {@code @Where(clause = "deleted_at IS NULL")}, so all queries
 * exclude logically-deleted projects automatically.
 */
@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /**
     * List all active projects in the current tenant (RLS-filtered automatically).
     * Used for the {@code GET /projects} endpoint.
     *
     * <p>This method intentionally has no explicit tenant_id parameter — the RLS policy
     * provides isolation. This is the pattern that Goal 7's test validates.
     */
    List<Project> findAllByOrderByCreatedAtDesc();

    /**
     * Find projects created by a specific user within the current tenant.
     * Combined with the RLS filter, this safely returns only projects owned by
     * {@code createdById} that also belong to the current tenant.
     */
    List<Project> findAllByCreatedById(UUID createdById);

    /**
     * Count active projects for the current tenant.
     * Used by UsageGuard (Phase 4) to enforce the {@code max_projects} plan limit
     * before allowing a new project to be created.
     */
    long countByTenantId(UUID tenantId);
}
