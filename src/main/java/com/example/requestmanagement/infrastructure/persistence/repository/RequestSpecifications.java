package com.example.requestmanagement.infrastructure.persistence.repository;

import com.example.requestmanagement.application.port.RequestSearchCriteria;
import com.example.requestmanagement.domain.model.RequestStatus;
import com.example.requestmanagement.infrastructure.persistence.entity.RequestEntity;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

public final class RequestSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private RequestSpecifications() {
    }

    public static Specification<RequestEntity> matching(RequestSearchCriteria criteria) {
        return Specification.where(hasStatus(criteria.status())).and(nameContains(criteria.name()));
    }

    private static Specification<RequestEntity> hasStatus(RequestStatus status) {
        if (status == null) {
            return (root, query, cb) -> cb.notEqual(root.get("status"), RequestStatus.DELETED);
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private static Specification<RequestEntity> nameContains(String name) {
        if (name == null) {
            return null;
        }
        String pattern = "%" + escapeLike(name.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, LIKE_ESCAPE);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
