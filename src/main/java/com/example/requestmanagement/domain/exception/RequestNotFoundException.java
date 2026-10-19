package com.example.requestmanagement.domain.exception;

public class RequestNotFoundException extends DomainException {

    public RequestNotFoundException(Long requestId) {
        super("Request " + requestId + " not found");
    }
}
