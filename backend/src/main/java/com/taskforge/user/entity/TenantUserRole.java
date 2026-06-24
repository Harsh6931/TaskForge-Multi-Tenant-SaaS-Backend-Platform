package com.taskforge.user.entity;

/**
 * TenantUserRole — Role a user holds within a specific tenant (organisation).
 *
 * <p>Roles are hierarchical in terms of permission scope:
 * <pre>
 *   ADMIN    → full control (invite, billing, delete project, view audit log)
 *   MANAGER  → create/delete projects and tasks, assign members
 *   MEMBER   → create/edit own tasks, post comments
 *   VIEWER   → read-only access to projects and tasks
 * </pre>
 *
 * <p>The full permission matrix is documented in {@code docs/RBAC.md} (Phase 2).
 * Spring's {@code @PreAuthorize} checks will reference these roles.
 *
 * <p>Stored as a Postgres ENUM {@code tenant_user_role} (defined in V1 migration).
 * JPA maps it via {@code @Enumerated(EnumType.STRING)} so the string representation
 * ("ADMIN", "MANAGER", etc.) is persisted — not the ordinal integer.
 * This makes the DB data human-readable and safe to reorder in the future.
 */
public enum TenantUserRole {

    /** Full administrative control over the tenant workspace. */
    ADMIN,

    /** Can manage projects, tasks, and team members. */
    MANAGER,

    /** Standard contributor — creates and edits tasks. */
    MEMBER,

    /** Read-only access — cannot create or modify any resources. */
    VIEWER
}
