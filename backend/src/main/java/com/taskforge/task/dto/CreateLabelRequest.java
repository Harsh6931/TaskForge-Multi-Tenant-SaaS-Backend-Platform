package com.taskforge.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * CreateLabelRequest — validated request body for {@code POST /labels}.
 *
 * @param name  display name of the label (e.g., "Bug", "Feature")
 * @param color hex colour code in {@code #RRGGBB} format (e.g., "#FF5733")
 */
public record CreateLabelRequest(

    @NotBlank(message = "Label name must not be blank")
    @Size(max = 255, message = "Label name must not exceed 255 characters")
    String name,

    @NotBlank(message = "Color is required")
    @Pattern(
        regexp = "^#[0-9A-Fa-f]{6}$",
        message = "Color must be a valid 6-digit hex code, e.g. #FF5733"
    )
    String color
) {}
