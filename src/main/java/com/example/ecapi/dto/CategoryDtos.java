package com.example.ecapi.dto;

import com.example.ecapi.domain.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class CategoryDtos {

    private CategoryDtos() {
    }

    public record CategoryRequest(
            @NotBlank String name,
            @NotBlank @Pattern(regexp = "[a-z0-9-]+", message = "slug must be lowercase letters, numbers, or hyphens")
            String slug) {
    }

    public record CategoryResponse(Long id, String name, String slug) {
        public static CategoryResponse from(Category category) {
            if (category == null) {
                return null;
            }
            return new CategoryResponse(category.getId(), category.getName(), category.getSlug());
        }
    }
}
