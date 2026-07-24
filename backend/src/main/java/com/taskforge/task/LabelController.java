package com.taskforge.task;

import com.taskforge.task.dto.CreateLabelRequest;
import com.taskforge.task.dto.LabelResponse;
import com.taskforge.tenant.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * LabelController — REST layer for {@code /labels} endpoints.
 */
@RestController
@RequestMapping("/labels")
@RequiredArgsConstructor
public class LabelController {

    private final LabelService labelService;

    // ── GET /labels ──────────────────────────────────────────────────────────

    /**
     * Returns all labels available in the current tenant.
     * All roles can list labels — needed to populate the label picker in the UI.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public List<LabelResponse> listLabels(@AuthenticationPrincipal UUID userId) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return labelService.listLabels(tenantId);
    }

    // ── POST /labels ──────────────────────────────────────────────────────────

    /**
     * Creates a new label in the current tenant.
     * Restricted to ADMIN and MANAGER (label management is a workspace-level concern).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public LabelResponse createLabel(
            @Valid @RequestBody CreateLabelRequest request,
            @AuthenticationPrincipal UUID userId
    ) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return labelService.createLabel(tenantId, request);
    }

    // ── DELETE /labels/{id} ──────────────────────────────────────────────────────

    /**
     * Hard-deletes a label (and cascades removal from all task_labels rows).
     * Restricted to ADMIN and MANAGER.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public void deleteLabel(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId
    ) {
        UUID tenantId = TenantContextHolder.getTenantId();
        labelService.deleteLabel(id, tenantId);
    }
}
