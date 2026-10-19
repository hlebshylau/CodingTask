package com.example.requestmanagement.api.dto;

import com.example.requestmanagement.domain.model.Request;
import com.example.requestmanagement.domain.model.RequestStatus;

import java.time.Instant;

public record RequestResponse(
        Long id,
        String name,
        String body,
        RequestStatus status,
        Long publicationId,
        Instant createdAt,
        Instant updatedAt
) {

    public static RequestResponse from(Request request) {
        return new RequestResponse(
                request.getId(),
                request.getName(),
                request.getBody(),
                request.getStatus(),
                request.getPublicationId(),
                request.getCreatedAt(),
                request.getUpdatedAt());
    }
}
