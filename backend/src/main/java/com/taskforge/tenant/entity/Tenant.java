package com.taskforge.tenant.entity;

import com.taskforge.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * Tenant — represents an organisation / company workspace in the multi-tenant system.
 *
 * <p><b>Multi-tenancy model:</b>
 * TaskForge uses a shared-schema, shared-database strategy where every tenant's data
 * lives in the same tables, distinguished by {@code tenant_id}. RLS policies (V7 migration)
 * enforce isolation at the database layer — this entity exists within that model.
 *
 * <p><b>Why no {@code tenant_id} on this entity?</b>
 * {@code tenants} is a global, root-level table — it IS the tenant. It doesn't belong to
 * another tenant, so no RLS policy applies to it (as documented in V7__enable_rls.sql).
 *
 * <p><b>Soft-delete:</b>
 * The {@code @Where} annotation filters out rows where {@code deleted_at IS NOT NULL}
 * from all JPA queries automatically. To delete a tenant, call
 * {@code SoftDeleteService.softDelete(tenant)} — never {@code repository.delete()}.
 *
 * <p><b>plan_id:</b>
 * Nullable FK to {@code plans} — null until a subscription is created. The FK constraint
 * is added in the V3 migration after the plans table exists.
 */
@Entity
@Table(name = "tenants")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant extends BaseEntity {

    /**
     * Human-readable organisation name (e.g., "Acme Corp").
     * Displayed in UI; not required to be unique.
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * URL-safe unique identifier for the tenant (e.g., "acme-corp").
     * Used in subdomain routing and API paths. Enforced unique by DB constraint.
     */
    @Column(name = "slug", nullable = false, unique = true, length = 255)
    private String slug;

    /**
     * FK to the {@code plans} table. Null until the tenant subscribes to a plan.
     * Loaded lazily — we rarely need the plan from the Tenant entity directly;
     * use the Subscription entity for billing context.
     *
     * <p>Stored as a raw UUID rather than a {@code @ManyToOne Plan} association to avoid
     * accidental eager loading of the plan on every Tenant fetch. Phase 4 will add the
     * full Plan entity association.
     */
    @Column(name = "plan_id")
    private UUID planId;
}
