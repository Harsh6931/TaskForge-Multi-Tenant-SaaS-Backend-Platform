package com.taskforge.task.entity;

import com.taskforge.common.BaseEntity;
import com.taskforge.tenant.entity.Tenant;
import com.taskforge.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * Comment — a user message attached to a task.
 *
 * <p><b>Tenant scoping:</b>
 * Every comment is tenant-scoped. RLS on the {@code comments} table (V7 migration)
 * ensures cross-tenant access is blocked at the database level.
 *
 * <p><b>Soft-delete:</b>
 * {@code @Where(clause = "deleted_at IS NULL")} hides deleted comments from all queries.
 * Authors and privileged roles (ADMIN, MANAGER) can soft-delete comments — enforcement
 * is in {@link com.taskforge.task.CommentService#deleteComment}.
 *
 * <p><b>Immutability design:</b>
 * {@code task} and {@code author} associations use {@code updatable = false} on their
 * join columns — a comment cannot be reassigned to a different task or attributed to
 * a different user after creation.
 */
@Entity
@Table(name = "comments")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comment extends BaseEntity {

    /**
     * The tenant this comment belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    /**
     * Read-only FK accessor for the tenant UUID.
     */
    @Column(name = "tenant_id", insertable = false, updatable = false)
    private UUID tenantId;

    /**
     * The task this comment belongs to. Immutable after creation.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    private Task task;

    /**
     * The user who wrote this comment. Immutable after creation.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User author;

    /**
     * The comment text. Stored as TEXT — no length limit enforced at DB level.
     */
    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;
}
