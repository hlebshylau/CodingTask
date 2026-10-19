package com.example.requestmanagement.application.port;

import com.example.requestmanagement.domain.model.AuditEntry;

import java.util.List;

public interface AuditRepository {

    AuditEntry save(AuditEntry entry);

    List<AuditEntry> findByRequestIdChronologically(Long requestId);
}
