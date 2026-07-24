package com.taskforge.task.dto;

import com.taskforge.task.entity.TaskPriority;
import com.taskforge.task.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * UpdateTaskRequest — validated request body for {@code PATCH /tasks/{id}}.
 *
 * <p>All fields except {@code version} are optional (nullable). Only non-null fields
 * are applied to the entity — PATCH semantics.
 *
 * <p>{@code version} is required and used for optimistic locking: the client must
 * echo back the version it last read. If the stored version differs, a 409 Conflict
 * is returned and the client must re-fetch before retrying.
 *
 * @param title      new title, or {@code null} to leave unchanged
 * @param description new description, or {@code null} to leave unchanged
 * @param status     desired new status; validated against the state machine in TaskService
 * @param priority   new priority, or {@code null} to leave unchanged
 * @param assigneeId new assignee UUID, or {@code null} to leave unchanged
 * @param dueDate    new due date, or {@code null} to leave unchanged
 * @param version    REQUIRED — the current version the client has; used for optimistic locking
 */
public record UpdateTaskRequest(

    @Size(max = 255, message = "Task title must not exceed 255 characters")
    String title,

    String description,

    TaskStatus status,

    TaskPriority priority,

    UUID assigneeId,

    LocalDate dueDate,

    @NotNull(message = "version is required for PATCH — echo back the version you last read")
    Integer version
) {}
