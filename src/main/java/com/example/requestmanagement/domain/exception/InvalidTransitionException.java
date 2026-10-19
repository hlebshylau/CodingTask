package com.example.requestmanagement.domain.exception;

import com.example.requestmanagement.domain.model.RequestEvent;
import com.example.requestmanagement.domain.model.RequestStatus;

public class InvalidTransitionException extends DomainException {

    private final RequestStatus currentState;
    private final RequestEvent event;

    public InvalidTransitionException(RequestStatus currentState, RequestEvent event) {
        super("Cannot apply " + event + " event in " + currentState + " state");
        this.currentState = currentState;
        this.event = event;
    }

    public RequestStatus getCurrentState() {
        return currentState;
    }

    public RequestEvent getEvent() {
        return event;
    }
}
