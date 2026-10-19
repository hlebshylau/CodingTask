package com.example.requestmanagement.infrastructure.persistence.repository;

import com.example.requestmanagement.infrastructure.persistence.entity.AuditEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEntryJpaRepository extends JpaRepository<AuditEntryEntity, Long> {

    List<AuditEntryEntity> findByRequestIdOrderByOccurredAtAscIdAsc(Long requestId);
}
