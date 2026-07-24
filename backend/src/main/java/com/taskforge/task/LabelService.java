package com.taskforge.task;

import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.task.dto.CreateLabelRequest;
import com.taskforge.task.dto.LabelResponse;
import com.taskforge.task.entity.Label;
import com.taskforge.task.repository.LabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * LabelService — business logic for label CRUD operations.
 *
 * <p><b>Hard delete:</b>
 * Labels have no soft-delete per SCHEMA.md. Deletion physically removes the row
 * and cascades to {@code task_labels} (ON DELETE CASCADE in V2 migration).
 *
 * <p><b>Tenant ownership check:</b>
 * Delete verifies the label belongs to the current tenant before deletion,
 * preventing one tenant from deleting another's labels.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class LabelService {

    private final LabelRepository labelRepository;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a new label for the current tenant.
     *
     * @param tenantId the current tenant (from JWT via TenantContextHolder)
     * @param request  the label name and colour
     * @return the created label as a response DTO
     */
    public LabelResponse createLabel(UUID tenantId, CreateLabelRequest request) {
        Label label = Label.builder()
                .tenantId(tenantId)
                .name(request.name())
                .color(request.color())
                .build();

        label = labelRepository.save(label);
        log.info("Label created: labelId={} tenantId={} name={}", label.getId(), tenantId, label.getName());

        return LabelResponse.from(label);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /**
     * Returns all labels belonging to the current tenant.
     *
     * @param tenantId the current tenant UUID
     * @return list of label response DTOs
     */
    @Transactional(readOnly = true)
    public List<LabelResponse> listLabels(UUID tenantId) {
        return labelRepository.findAllByTenantId(tenantId)
                .stream()
                .map(LabelResponse::from)
                .toList();
    }

    // ── Delete ──────────────────────────────────────────────────────────────

    /**
     * Hard-deletes a label and removes all task associations via ON DELETE CASCADE.
     *
     * <p>Verifies the label belongs to the current tenant before deletion.
     * This prevents tenant A from deleting tenant B's labels even if they know the UUID.
     *
     * @param labelId  the label to delete
     * @param tenantId the current tenant (used to verify ownership)
     * @throws ResourceNotFoundException if no label with that ID exists in this tenant
     */
    public void deleteLabel(UUID labelId, UUID tenantId) {
        Label label = labelRepository.findById(labelId)
                .orElseThrow(() -> new ResourceNotFoundException("Label", labelId));

        // Ownership check: ensure the label belongs to this tenant
        if (!label.getTenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("Label", labelId);
        }

        labelRepository.delete(label);
        log.info("Label hard-deleted: labelId={} tenantId={}", labelId, tenantId);
    }
}
