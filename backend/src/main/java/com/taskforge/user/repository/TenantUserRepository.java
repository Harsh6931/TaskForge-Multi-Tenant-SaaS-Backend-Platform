package com.taskforge.user.repository;

import com.taskforge.user.entity.TenantUser;
import com.taskforge.user.entity.TenantUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TenantUserRepository — data access for the {@link TenantUser} join entity.
 *
 * <p>{@code TenantUser} records which users are members of which tenants, and what role
 * each holds. This repository is the primary source of truth for:
 * <ul>
 *   <li>Determining what tenants a user belongs to (shown at login)</li>
 *   <li>Verifying a user's role before authorising a privileged action</li>
 *   <li>Listing all members of a tenant (admin management screen)</li>
 * </ul>
 *
 * <p><b>No soft-delete here:</b>
 * {@link TenantUser} does not extend {@link com.taskforge.common.BaseEntity} and has no
 * {@code deleted_at} column — membership rows are hard-deleted when a user leaves a tenant.
 *
 * <p><b>RLS note:</b>
 * {@code tenant_users} is RLS-protected (V7 migration). Queries here are automatically
 * filtered to the current tenant session variable set by
 * {@link com.taskforge.tenant.TenantConnectionInterceptor}.
 */
@Repository
public interface TenantUserRepository extends JpaRepository<TenantUser, UUID> {

    /**
     * Find all tenants a user belongs to.
     * Called at login to build the tenant list returned in the auth response
     * (per API contract: {@code POST /auth/login → { tenants[] }}).
     */
    List<TenantUser> findAllByUserId(UUID userId);

    /**
     * Find a specific membership record — used for role lookups and
     * the /auth/switch-tenant endpoint to verify the user is actually a member
     * before issuing a new tenant-scoped token.
     */
    Optional<TenantUser> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    /**
     * List all members of a given tenant — used by the admin management screen.
     * Returns all roles; callers can filter by role if needed.
     */
    List<TenantUser> findAllByTenantId(UUID tenantId);

    /**
     * Check whether a user has a specific role in a tenant.
     * Useful for quick permission checks without loading the full TenantUser object.
     */
    boolean existsByTenantIdAndUserIdAndRole(UUID tenantId, UUID userId, TenantUserRole role);

    /**
     * Count the active members of a tenant — used by UsageGuard (Phase 4) to enforce
     * the {@code max_seats} plan limit before allowing new user invitations.
     */
    long countByTenantId(UUID tenantId);
}
