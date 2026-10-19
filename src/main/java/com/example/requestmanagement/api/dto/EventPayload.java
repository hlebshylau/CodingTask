package com.example.requestmanagement.api.dto;

import com.example.requestmanagement.domain.model.RequestEvent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EventPayload(@NotNull RequestEvent event, @Size(max = 1000) String reason) {
}
