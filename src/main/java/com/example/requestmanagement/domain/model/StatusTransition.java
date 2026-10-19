package com.example.requestmanagement.domain.model;

import java.time.Instant;

public record StatusTransition(
        Long requestId,
        RequestStatus previousStatus,
        RequestStatus newStatus,
        RequestEvent event,
        String reason,
        Instant occurredAt
) {
}
