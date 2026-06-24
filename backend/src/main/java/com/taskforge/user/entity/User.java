package com.taskforge.user.entity;

import com.taskforge.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

/**
 * User — represents a person who can belong to one or more tenant workspaces.
 *
 * <p><b>Why no tenant_id here?</b>
 * A user is a global identity — they can be a member of multiple tenants (like a Slack
 * user joining multiple workspaces). The tenant membership and role is recorded in the
 * {@code tenant_users} join table, represented by the {@link TenantUser} entity.
 * This means {@code users} is NOT a tenant-scoped table and has no RLS policy.
 *
 * <p><b>Security note on passwordHash:</b>
 * This field stores the BCrypt hash of the password — never the plaintext.
 * The field is named {@code passwordHash} (not {@code password}) as a deliberate reminder.
 * Spring Security's {@code PasswordEncoder} (BCryptPasswordEncoder) is used exclusively
 * in Phase 2's AuthService to encode and verify passwords.
 *
 * <p><b>Soft-delete:</b>
 * Deleting a user sets {@code deleted_at}; {@code @Where} filters them from all queries.
 * Their historical audit log entries, comments, and task assignments remain intact.
 *
 * <p><b>Why @Column(name="password_hash") and not relying on naming strategy?</b>
 * Spring Boot's default naming strategy converts camelCase to snake_case automatically,
 * so {@code passwordHash} → {@code password_hash}. The explicit {@code @Column} annotation
 * makes the mapping self-documenting and immune to naming strategy configuration changes.
 */
@Entity
@Table(name = "users")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    /**
     * Unique email address — used as the login credential identifier.
     * Max 320 chars per RFC 5321.
     */
    @Column(name = "email", nullable = false, unique = true, length = 320)
    private String email;

    /**
     * BCrypt hash of the user's password. NEVER store or log plaintext passwords.
     * Phase 2 AuthService will use {@code BCryptPasswordEncoder.matches(raw, hash)}.
     */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /**
     * User's display name shown in the UI (e.g., "Jane Smith").
     */
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;
}
