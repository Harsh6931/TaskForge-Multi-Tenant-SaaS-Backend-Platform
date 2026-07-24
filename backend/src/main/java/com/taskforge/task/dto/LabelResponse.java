package com.taskforge.task.dto;

import com.taskforge.task.entity.Label;

import java.util.UUID;

/**
 * LabelResponse — JSON representation of a label.
 *
 * @param id    the label's UUID
 * @param name  display name (e.g., "Bug", "Feature")
 * @param color hex colour code (e.g., "#FF5733")
 */
public record LabelResponse(
    UUID id,
    String name,
    String color
) {

    /**
     * Maps a {@link Label} entity to a {@link LabelResponse} DTO.
     *
     * @param label the label entity
     * @return a new LabelResponse
     */
    public static LabelResponse from(Label label) {
        return new LabelResponse(label.getId(), label.getName(), label.getColor());
    }
}
