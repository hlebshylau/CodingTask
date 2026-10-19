package com.example.requestmanagement.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRequestPayload(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 4000) String body
) {
}
