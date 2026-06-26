package com.taskforge.user.repository;

import com.taskforge.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * UserRepository — data access for the {@link User} entity.
 *
 * <p><b>Soft-delete is transparent:</b>
 * {@link User} carries {@code @Where(clause = "deleted_at IS NULL")}, so all Spring Data
 * queries silently exclude soft-deleted users. A user who has been soft-deleted cannot
 * log in or be found by email — as intended.
 *
 * <p><b>No tenant-scoping needed:</b>
 * {@code users} is a global table (no {@code tenant_id}). The same user row is shared
 * across all the tenants they belong to — membership is expressed via {@link
 * com.taskforge.user.entity.TenantUser}.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Look up a user by their email address.
     * Primary lookup path during authentication — Phase 2 AuthService will call this
     * to load the user before verifying the password hash.
     *
     * <p>Returns {@code Optional.empty()} for unknown emails AND for soft-deleted users
     * (because of the {@code @Where} filter) — callers cannot distinguish the two cases
     * intentionally, to prevent user-enumeration attacks.
     */
    Optional<User> findByEmail(String email);

    /**
     * Fast existence check used during signup to reject duplicate emails
     * before attempting an INSERT (avoids catching a DB unique-constraint exception).
     */
    boolean existsByEmail(String email);
}
