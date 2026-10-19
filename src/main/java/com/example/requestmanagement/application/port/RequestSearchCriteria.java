package com.example.requestmanagement.application.port;

import com.example.requestmanagement.domain.model.RequestStatus;

public record RequestSearchCriteria(String name, RequestStatus status, int page, int size) {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;

    public RequestSearchCriteria {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        name = name == null || name.isBlank() ? null : name.strip();
    }
}
