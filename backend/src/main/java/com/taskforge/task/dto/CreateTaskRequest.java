package com.taskforge.task.dto;

import com.taskforge.task.entity.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * CreateTaskRequest — validated request body for {@code POST /projects/{id}/tasks}.
 *
 * <p>Status is NOT included — all tasks start as {@code BACKLOG} on creation.
 * This enforces the state machine at the API boundary; callers cannot bypass
 * the status machine by creating a task in DONE state.
 *
 * @param title       short task title (required, max 255 chars)
 * @param description optional detailed description
 * @param priority    urgency level (required)
 * @param assigneeId  optional user UUID to assign the task to
 * @param dueDate     optional target completion date
 */
public record CreateTaskRequest(

    @NotBlank(message = "Task title must not be blank")
    @Size(max = 255, message = "Task title must not exceed 255 characters")
    String title,

    String description,

    @NotNull(message = "Priority is required")
    TaskPriority priority,

    UUID assigneeId,

    LocalDate dueDate
) {}
