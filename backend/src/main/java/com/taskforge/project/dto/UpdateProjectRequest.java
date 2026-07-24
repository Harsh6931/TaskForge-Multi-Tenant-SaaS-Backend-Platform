package com.taskforge.project.dto;

import jakarta.validation.constraints.Size;

/**
 * UpdateProjectRequest — validated request body for {@code PATCH /projects/{id}}.
 *
 * <p>Both fields are optional (nullable). Only non-null fields are applied to the entity
 * — this is PATCH semantics, not PUT (which would require all fields to be present).
 *
 * <p>Example: sending {@code { "description": "updated desc" }} leaves the name unchanged.
 *
 * @param name        new display name, or {@code null} to leave unchanged
 * @param description new description, or {@code null} to leave unchanged
 */
public record UpdateProjectRequest(

    @Size(max = 255, message = "Project name must not exceed 255 characters")
    String name,

    String description
) {}
