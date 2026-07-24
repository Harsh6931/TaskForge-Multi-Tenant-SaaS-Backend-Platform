package com.taskforge.task;

import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.TaskPageResponse;
import com.taskforge.task.dto.TaskResponse;
import com.taskforge.task.dto.UpdateTaskRequest;
import com.taskforge.task.entity.TaskStatus;
import com.taskforge.tenant.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * TaskController — REST layer for task-related endpoints.
 *
 * <p>Endpoints are split across two URL patterns:
 * <ul>
 *   <li>{@code /projects/{projectId}/tasks} — task collection scoped to a project</li>
 *   <li>{@code /tasks/{id}} — single-task operations</li>
 * </ul>
 *
 * <p><b>RBAC:</b> Enforced via {@code @PreAuthorize} per the RBAC matrix in docs/RBAC.md.
 *
 * <p><b>Cursor pagination:</b>
 * {@code GET /projects/{id}/tasks} returns a {@link TaskPageResponse} with an optional
 * {@code nextCursor}. The client passes it back as {@code ?cursor=<token>} to fetch the
 * next page. Limit is capped at 100 to prevent unbounded result sets.
 */
@RestController
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    // ── GET /projects/{projectId}/tasks ───────────────────────────────────────

    /**
     * Returns a cursor-paginated list of tasks for a project.
     *
     * @param projectId  the project UUID
     * @param cursor     opaque cursor from previous page (omit for first page)
     * @param limit      max tasks per page, capped at 100
     * @param status     optional status filter
     * @param assigneeId optional assignee filter
     */
    @GetMapping("/projects/{projectId}/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public TaskPageResponse listTasks(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Max(100) int limit,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) UUID assigneeId
    ) {
        return taskService.listTasksForProject(projectId, cursor, limit, status, assigneeId);
    }

    // ── POST /projects/{projectId}/tasks ──────────────────────────────────────

    /**
     * Creates a new task in a project. All tasks start as BACKLOG.
     * MEMBER and above can create tasks (VIEWERs cannot).
     */
    @PostMapping("/projects/{projectId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public TaskResponse createTask(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateTaskRequest request,
            @AuthenticationPrincipal UUID userId
    ) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return taskService.createTask(projectId, tenantId, request);
    }

    // ── GET /tasks/{id} ───────────────────────────────────────────────────────

    /**
     * Returns a single task by its UUID.
     */
    @GetMapping("/tasks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public TaskResponse getTask(@PathVariable UUID id) {
        return taskService.getTask(id);
    }

    // ── PATCH /tasks/{id} ─────────────────────────────────────────────────────

    /**
     * Partially updates a task. Requires the client to echo back the current {@code version}
     * for optimistic locking. Returns 409 on version mismatch, 422 on invalid status transition.
     */
    @PatchMapping("/tasks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public TaskResponse updateTask(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTaskRequest request
    ) {
        return taskService.updateTask(id, request);
    }

    // ── DELETE /tasks/{id} ────────────────────────────────────────────────────

    /**
     * Soft-deletes a task. Restricted to ADMIN and MANAGER (per RBAC matrix).
     */
    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public void deleteTask(@PathVariable UUID id) {
        taskService.deleteTask(id);
    }

    // ── POST /tasks/{id}/labels/{labelId} ─────────────────────────────────────

    /**
     * Attaches a label to a task. Verifies the label belongs to the current tenant.
     */
    @PostMapping("/tasks/{id}/labels/{labelId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public TaskResponse addLabel(
            @PathVariable UUID id,
            @PathVariable UUID labelId,
            @AuthenticationPrincipal UUID userId
    ) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return taskService.addLabelToTask(id, labelId, tenantId);
    }

    // ── DELETE /tasks/{id}/labels/{labelId} ───────────────────────────────────

    /**
     * Detaches a label from a task.
     */
    @DeleteMapping("/tasks/{id}/labels/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public void removeLabel(
            @PathVariable UUID id,
            @PathVariable UUID labelId
    ) {
        taskService.removeLabelFromTask(id, labelId);
    }

    // ── GET /tasks/search ─────────────────────────────────────────────────────

    /**
     * Full-text search across task titles and descriptions within the current tenant.
     * Results are ranked by relevance (title matches outrank description matches).
     *
     * @param q     the raw search query
     * @param limit maximum results, capped at 50
     */
    @GetMapping("/tasks/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public List<TaskResponse> searchTasks(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") @Max(50) int limit,
            @AuthenticationPrincipal UUID userId
    ) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return taskService.searchTasks(tenantId, q, limit);
    }
}
