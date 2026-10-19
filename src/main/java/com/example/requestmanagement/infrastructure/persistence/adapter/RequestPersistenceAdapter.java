package com.example.requestmanagement.infrastructure.persistence.adapter;

import com.example.requestmanagement.application.port.PageResult;
import com.example.requestmanagement.application.port.RequestRepository;
import com.example.requestmanagement.application.port.RequestSearchCriteria;
import com.example.requestmanagement.domain.model.Request;
import com.example.requestmanagement.infrastructure.persistence.entity.RequestEntity;
import com.example.requestmanagement.infrastructure.persistence.repository.RequestJpaRepository;
import com.example.requestmanagement.infrastructure.persistence.repository.RequestSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Component
public class RequestPersistenceAdapter implements RequestRepository {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final RequestJpaRepository jpaRepository;

    public RequestPersistenceAdapter(RequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Request save(Request request) {
        RequestEntity entity = request.getId() == null ? newEntity(request) : managedEntity(request);
        entity.setBody(request.getBody());
        entity.setStatus(request.getStatus());
        entity.setPublicationId(request.getPublicationId());
        entity.setUpdatedAt(request.getUpdatedAt());
        return toDomain(jpaRepository.saveAndFlush(entity));
    }

    @Override
    public Optional<Request> findById(Long id) {
        return jpaRepository.findById(id).map(RequestPersistenceAdapter::toDomain);
    }

    @Override
    public boolean existsById(Long id) {
        return jpaRepository.existsById(id);
    }

    @Override
    public PageResult<Request> search(RequestSearchCriteria criteria) {
        PageRequest pageable = PageRequest.of(criteria.page(), criteria.size(), DEFAULT_SORT);
        Page<RequestEntity> page = jpaRepository.findAll(RequestSpecifications.matching(criteria), pageable);
        return new PageResult<>(
                page.getContent().stream().map(RequestPersistenceAdapter::toDomain).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    private static RequestEntity newEntity(Request request) {
        RequestEntity entity = new RequestEntity();
        entity.setName(request.getName());
        entity.setCreatedAt(request.getCreatedAt());
        return entity;
    }

    private RequestEntity managedEntity(Request request) {
        RequestEntity entity = jpaRepository.findById(request.getId())
                .orElseThrow(() -> new ObjectOptimisticLockingFailureException(RequestEntity.class, request.getId()));
        if (!Objects.equals(entity.getVersion(), request.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(RequestEntity.class, request.getId());
        }
        return entity;
    }

    private static Request toDomain(RequestEntity entity) {
        return Request.restore(
                entity.getId(),
                entity.getName(),
                entity.getBody(),
                entity.getStatus(),
                entity.getPublicationId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
    }
}
