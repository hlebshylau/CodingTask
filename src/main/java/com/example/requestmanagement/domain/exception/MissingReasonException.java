package com.example.requestmanagement.domain.exception;

import com.example.requestmanagement.domain.model.RequestEvent;

public class MissingReasonException extends DomainValidationException {

    public MissingReasonException(RequestEvent event) {
        super("A non-blank reason is required for " + event + " event");
    }
}
