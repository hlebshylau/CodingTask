package com.example.requestmanagement.infrastructure.persistence.repository;

import com.example.requestmanagement.infrastructure.persistence.entity.RequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RequestJpaRepository extends JpaRepository<RequestEntity, Long>, JpaSpecificationExecutor<RequestEntity> {
}
