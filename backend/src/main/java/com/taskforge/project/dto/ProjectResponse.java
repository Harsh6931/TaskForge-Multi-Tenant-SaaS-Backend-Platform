package com.taskforge.project.dto;

import com.taskforge.project.entity.Project;

import java.time.Instant;
import java.util.UUID;

/**
 * ProjectResponse — the JSON shape returned by all project endpoints.
 *
 * <p>Contains only safe, client-facing fields — no internal FKs or audit timestamps
 * that clients have no use for. The static factory method {@link #from(Project)} keeps
 * mapping logic out of the service layer.
 *
 * @param id          the project's UUID
 * @param name        display name
 * @param description optional free-text description
 * @param createdById UUID of the user who created the project
 * @param createdAt   UTC timestamp of creation
 * @param updatedAt   UTC timestamp of the last update
 */
public record ProjectResponse(
    UUID id,
    String name,
    String description,
    UUID createdById,
    Instant createdAt,
    Instant updatedAt
) {

    /**
     * Maps a {@link Project} entity to a {@link ProjectResponse} DTO.
     *
     * <p>Accessing {@code project.getCreatedBy().getId()} triggers a lazy load if the
     * {@code createdBy} proxy is not already initialised. Callers must ensure this is
     * called within an active JPA transaction (i.e., inside a service method).
     *
     * @param project the project entity to map
     * @return a new ProjectResponse
     */
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
            project.getId(),
            project.getName(),
            project.getDescription(),
            project.getCreatedBy().getId(),
            project.getCreatedAt(),
            project.getUpdatedAt()
        );
    }
}
