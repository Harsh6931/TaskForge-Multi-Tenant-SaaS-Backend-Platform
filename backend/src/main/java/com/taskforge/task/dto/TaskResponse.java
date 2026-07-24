package com.taskforge.task.dto;

import com.taskforge.task.entity.Task;
import com.taskforge.task.entity.TaskPriority;
import com.taskforge.task.entity.TaskStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * TaskResponse — the JSON shape returned by all task endpoints.
 *
 * <p>{@code version} is always included in the response so the client can echo it
 * back on the next PATCH request (optimistic locking requirement).
 *
 * <p>{@code labels} is the full list of attached labels, serialised inline.
 * This avoids a separate GET /tasks/{id}/labels endpoint for the common case
 * of displaying task details with labels.
 *
 * @param id          the task's UUID
 * @param projectId   the project this task belongs to
 * @param title       task title
 * @param description optional description
 * @param status      current lifecycle status
 * @param priority    urgency level
 * @param assigneeId  UUID of the assigned user, or {@code null} if unassigned
 * @param dueDate     target completion date, or {@code null}
 * @param version     optimistic lock counter — must be echoed back on PATCH
 * @param labels      labels currently attached to this task
 * @param createdAt   UTC creation timestamp
 * @param updatedAt   UTC last-modified timestamp
 */
public record TaskResponse(
    UUID id,
    UUID projectId,
    String title,
    String description,
    TaskStatus status,
    TaskPriority priority,
    UUID assigneeId,
    LocalDate dueDate,
    Integer version,
    List<LabelResponse> labels,
    Instant createdAt,
    Instant updatedAt
) {

    /**
     * Maps a {@link Task} entity to a {@link TaskResponse} DTO.
     *
     * <p>Must be called within an active JPA transaction because it accesses
     * lazy associations ({@code task.getProject()}, {@code task.getAssignee()},
     * {@code task.getLabels()}).
     *
     * @param task the task entity to map
     * @return a new TaskResponse
     */
    public static TaskResponse from(Task task) {
        return new TaskResponse(
            task.getId(),
            task.getProject().getId(),
            task.getTitle(),
            task.getDescription(),
            task.getStatus(),
            task.getPriority(),
            task.getAssignee() != null ? task.getAssignee().getId() : null,
            task.getDueDate(),
            task.getVersion(),
            task.getLabels().stream().map(LabelResponse::from).toList(),
            task.getCreatedAt(),
            task.getUpdatedAt()
        );
    }
}
