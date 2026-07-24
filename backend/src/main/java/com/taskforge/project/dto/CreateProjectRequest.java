package com.taskforge.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CreateProjectRequest — validated request body for {@code POST /projects}.
 *
 * <p>{@code name} is required and capped at 255 chars (matching the DB column length).
 * {@code description} is optional — a project can be created without one and updated later.
 *
 * @param name        display name of the project (required)
 * @param description free-text description of the project's purpose (optional)
 */
public record CreateProjectRequest(

    @NotBlank(message = "Project name must not be blank")
    @Size(max = 255, message = "Project name must not exceed 255 characters")
    String name,

    String description
) {}
