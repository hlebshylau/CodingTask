package com.example.requestmanagement.infrastructure.persistence.adapter;

import com.example.requestmanagement.application.port.AuditRepository;
import com.example.requestmanagement.domain.model.AuditEntry;
import com.example.requestmanagement.infrastructure.persistence.entity.AuditEntryEntity;
import com.example.requestmanagement.infrastructure.persistence.repository.AuditEntryJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AuditPersistenceAdapter implements AuditRepository {

    private final AuditEntryJpaRepository jpaRepository;

    public AuditPersistenceAdapter(AuditEntryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public AuditEntry save(AuditEntry entry) {
        AuditEntryEntity entity = new AuditEntryEntity(
                entry.requestId(),
                entry.previousStatus(),
                entry.newStatus(),
                entry.event(),
                entry.reason(),
                entry.performedBy(),
                entry.timestamp());
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public List<AuditEntry> findByRequestIdChronologically(Long requestId) {
        return jpaRepository.findByRequestIdOrderByOccurredAtAscIdAsc(requestId).stream()
                .map(AuditPersistenceAdapter::toDomain)
                .toList();
    }

    private static AuditEntry toDomain(AuditEntryEntity entity) {
        return new AuditEntry(
                entity.getId(),
                entity.getRequestId(),
                entity.getPreviousStatus(),
                entity.getNewStatus(),
                entity.getEvent(),
                entity.getReason(),
                entity.getPerformedBy(),
                entity.getOccurredAt());
    }
}
