package com.example.requestmanagement.domain.exception;

import com.example.requestmanagement.domain.model.RequestStatus;

public class RequestNotEditableException extends DomainException {

    public RequestNotEditableException(Long requestId, RequestStatus status) {
        super("Request " + requestId + " cannot be modified in " + status + " state");
    }
}
