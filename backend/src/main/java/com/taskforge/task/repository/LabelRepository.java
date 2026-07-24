package com.taskforge.task.repository;

import com.taskforge.task.entity.Label;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * LabelRepository — data access for the {@link Label} entity.
 *
 * <p>Labels have no soft-delete — deletion is a hard {@code DELETE} via
 * {@link #delete(Object)}. The join table {@code task_labels} has an
 * {@code ON DELETE CASCADE} so task associations are cleaned up automatically.
 *
 * <p>RLS on the {@code labels} table (V7 migration) scopes queries to the current tenant.
 */
@Repository
public interface LabelRepository extends JpaRepository<Label, UUID> {

    /**
     * Returns all labels belonging to a tenant.
     * Used by {@code GET /labels} to list available labels for the workspace.
     *
     * @param tenantId the current tenant's UUID
     */
    List<Label> findAllByTenantId(UUID tenantId);

    /**
     * Security gate — verifies a label belongs to a specific tenant before attachment.
     * Used by {@link com.taskforge.task.TaskService#addLabelToTask} to prevent a
     * tenant from attaching another tenant's label to their tasks.
     *
     * @param id       the label's UUID
     * @param tenantId the expected tenant UUID
     * @return true only if the label exists AND belongs to tenantId
     */
    boolean existsByIdAndTenantId(UUID id, UUID tenantId);
}
