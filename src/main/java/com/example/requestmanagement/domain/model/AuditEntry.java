package com.example.requestmanagement.domain.model;

import java.time.Instant;

public record AuditEntry(
        Long id,
        Long requestId,
        RequestStatus previousStatus,
        RequestStatus newStatus,
        RequestEvent event,
        String reason,
        String performedBy,
        Instant timestamp
) {

    public static AuditEntry of(StatusTransition transition, String performedBy) {
        return new AuditEntry(
                null,
                transition.requestId(),
                transition.previousStatus(),
                transition.newStatus(),
                transition.event(),
                transition.reason(),
                performedBy,
                transition.occurredAt());
    }
}
