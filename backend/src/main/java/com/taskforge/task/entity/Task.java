package com.taskforge.task.entity;

import com.taskforge.common.BaseEntity;
import com.taskforge.project.entity.Project;
import com.taskforge.tenant.entity.Tenant;
import com.taskforge.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Task — the core unit of work within a project.
 *
 * <p><b>Tenant scoping:</b>
 * Every task belongs to exactly one tenant. The {@code tenant_id} column is RLS-protected
 * (V7 migration) — queries automatically return only rows where
 * {@code tenant_id = current_setting('app.current_tenant_id')::uuid}.
 *
 * <p><b>Soft-delete:</b>
 * {@code @Where(clause = "deleted_at IS NULL")} filters deleted tasks from all JPA queries.
 * The partial index {@code idx_tasks_tenant_project WHERE deleted_at IS NULL} (V2 migration)
 * keeps this filter fast.
 *
 * <p><b>Optimistic locking:</b>
 * The {@code version} column is used for manual optimistic locking in
 * {@link com.taskforge.task.TaskService}. We do NOT use Hibernate's {@code @Version}
 * annotation because it throws {@code OptimisticLockException} deep inside the JPA flush
 * cycle, which is harder to translate to a clean 409 response. Instead, TaskService
 * performs an explicit compare-and-swap before saving and throws
 * {@link com.taskforge.common.exception.OptimisticLockConflictException} on mismatch.
 *
 * <p><b>Status state machine:</b>
 * Valid transitions are encoded on {@link TaskStatus} — call
 * {@code task.getStatus().canTransitionTo(newStatus)} before applying a status change.
 *
 * <p><b>Why @Enumerated(EnumType.STRING) with columnDefinition?</b>
 * Without {@code columnDefinition = "task_status"}, Hibernate defaults to VARCHAR and
 * PostgreSQL rejects it (the column is a PG ENUM type). The explicit columnDefinition
 * tells JDBC to cast correctly.
 */
@Entity
@Table(name = "tasks")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task extends BaseEntity {

    /**
     * The tenant this task belongs to.
     * Lazy-loaded — most operations only need the tenant UUID, not the full object.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    /**
     * Read-only FK accessor for the tenant UUID.
     * Avoids proxy initialisation when only the ID is needed.
     */
    @Column(name = "tenant_id", insertable = false, updatable = false)
    private UUID tenantId;

    /**
     * The project this task belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /**
     * Short, descriptive title of the task.
     */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /**
     * Optional detailed description. May contain markdown.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Current lifecycle status. Valid transitions enforced by {@link TaskStatus#canTransitionTo}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "task_status")
    private TaskStatus status;

    /**
     * Urgency level of the task.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, columnDefinition = "task_priority")
    private TaskPriority priority;

    /**
     * The user this task is assigned to. Nullable — tasks can be unassigned.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    /**
     * Optional due date. The time component is ignored — only the calendar date matters.
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /**
     * Optimistic locking version counter.
     * Incremented by TaskService on every successful PATCH.
     * Clients must echo back the version they last read; a mismatch triggers 409 Conflict.
     */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 0;

    /**
     * Labels attached to this task via the {@code task_labels} join table.
     * Initialised to an empty set to prevent NPE when adding labels to a new task.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "task_labels",
        joinColumns = @JoinColumn(name = "task_id"),
        inverseJoinColumns = @JoinColumn(name = "label_id")
    )
    @Builder.Default
    private Set<Label> labels = new HashSet<>();
}
