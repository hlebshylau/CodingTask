package com.example.requestmanagement.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateBodyPayload(@NotBlank @Size(max = 4000) String body) {
}
