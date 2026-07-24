package com.taskforge.task.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Label — a colour-coded tag that can be attached to multiple tasks.
 *
 * <p><b>Why no BaseEntity / soft-delete?</b>
 * Per SCHEMA.md design decision, labels are hard-deleted. They have no
 * {@code deleted_at} column and no audit trail requirement. Using BaseEntity
 * would add {@code createdAt}, {@code updatedAt}, {@code deletedAt} columns
 * that don't exist in the schema — so Label is a standalone entity.
 *
 * <p><b>Tenant scoping:</b>
 * Labels are tenant-scoped via {@code tenant_id}. The RLS policy on the
 * {@code labels} table (V7 migration) ensures cross-tenant access is blocked
 * at the database level.
 *
 * <p><b>No soft-delete — use with care:</b>
 * Deleting a label hard-deletes it and removes all rows from {@code task_labels}
 * via ON DELETE CASCADE (defined in V2 migration). Tasks that had the label
 * simply lose the association.
 */
@Entity
@Table(name = "labels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Label {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Tenant this label belongs to.
     * Not a FK association — stored as a plain UUID column to keep Label lightweight.
     */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /**
     * Display name shown in the UI (e.g., "Bug", "Feature", "Urgent").
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Hex colour code used to render the label chip (e.g., "#FF5733").
     * Validated at the API layer to be a valid 6-digit hex colour.
     */
    @Column(name = "color", nullable = false, length = 50)
    private String color;
}
