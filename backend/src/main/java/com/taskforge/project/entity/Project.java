package com.taskforge.project.entity;

import com.taskforge.common.BaseEntity;
import com.taskforge.tenant.entity.Tenant;
import com.taskforge.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * Project — a workspace container within a tenant that groups related tasks.
 *
 * <p><b>Tenant scoping:</b>
 * Every project belongs to exactly one tenant. The {@code tenant_id} column is indexed
 * and RLS-protected (V7 migration) — a query for projects will only return rows where
 * {@code tenant_id = current_setting('app.current_tenant_id')::uuid}. Even if the
 * application layer omits a WHERE clause, the DB enforces isolation automatically.
 *
 * <p><b>Soft-delete:</b>
 * {@code @Where(clause = "deleted_at IS NULL")} filters deleted projects from all JPA
 * queries. The partial index {@code idx_projects_tenant_id WHERE deleted_at IS NULL}
 * (V2 migration) ensures this filter is fast.
 *
 * <p><b>Relationship design choices:</b>
 * <ul>
 *   <li>{@code tenant} — {@code @ManyToOne LAZY}: we almost always have the tenant ID
 *       already; loading the full Tenant object would be wasteful on every project query.</li>
 *   <li>{@code createdBy} — {@code @ManyToOne LAZY}: same rationale — we store the UUID
 *       in responses but rarely need the full User object in the same transaction.</li>
 * </ul>
 *
 * <p>Tasks, Comments, and Labels that belong to this project are added in Phase 3.
 */
@Entity
@Table(name = "projects")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project extends BaseEntity {

    /**
     * The tenant this project belongs to. Used by RLS and for filtering.
     * Lazy-loaded — most operations only need the tenant_id, not the full object.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    /**
     * Convenience accessor for the tenant's UUID without triggering a lazy load.
     * Stored as a column-level copy because JPA doesn't let you read a FK column
     * value without initialising the proxy when only the association is mapped.
     *
     * <p>This is the "read-only FK" pattern: the actual FK column is owned by
     * the {@code tenant} association above; this field is {@code insertable=false,
     * updatable=false} so Hibernate doesn't try to write it twice.
     */
    @Column(name = "tenant_id", insertable = false, updatable = false)
    private UUID tenantId;

    /**
     * Project display name (e.g., "Backend API", "Marketing Q3").
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Optional free-text description of the project's purpose and scope.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * The user who originally created this project.
     * Immutable after creation ({@code updatable = false} on the join column).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;
}
