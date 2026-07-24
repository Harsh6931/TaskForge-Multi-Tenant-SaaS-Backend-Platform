package com.taskforge.task.entity;

/**
 * TaskPriority — the urgency level of a task.
 *
 * <p>Stored as a Postgres ENUM {@code task_priority} (defined in V2 migration).
 * JPA maps it via {@code @Enumerated(EnumType.STRING)} on the Task entity.
 */
public enum TaskPriority {
    LOW,
    MEDIUM,
    HIGH
}
