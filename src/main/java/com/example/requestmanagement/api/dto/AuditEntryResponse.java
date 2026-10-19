package com.example.requestmanagement.api.dto;

import com.example.requestmanagement.domain.model.AuditEntry;
import com.example.requestmanagement.domain.model.RequestEvent;
import com.example.requestmanagement.domain.model.RequestStatus;

import java.time.Instant;

public record AuditEntryResponse(
        Long id,
        Long requestId,
        RequestStatus previousStatus,
        RequestStatus newStatus,
        RequestEvent event,
        String reason,
        String performedBy,
        Instant timestamp
) {

    public static AuditEntryResponse from(AuditEntry entry) {
        return new AuditEntryResponse(
                entry.id(),
                entry.requestId(),
                entry.previousStatus(),
                entry.newStatus(),
                entry.event(),
                entry.reason(),
                entry.performedBy(),
                entry.timestamp());
    }
}
