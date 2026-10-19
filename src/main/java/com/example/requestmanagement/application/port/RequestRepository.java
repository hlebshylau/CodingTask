package com.example.requestmanagement.application.port;

import com.example.requestmanagement.domain.model.Request;

import java.util.Optional;

public interface RequestRepository {

    Request save(Request request);

    Optional<Request> findById(Long id);

    boolean existsById(Long id);

    PageResult<Request> search(RequestSearchCriteria criteria);
}
