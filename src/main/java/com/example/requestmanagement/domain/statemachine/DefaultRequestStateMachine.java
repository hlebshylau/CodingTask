package com.example.requestmanagement.domain.statemachine;

import com.example.requestmanagement.domain.exception.InvalidTransitionException;
import com.example.requestmanagement.domain.model.RequestEvent;
import com.example.requestmanagement.domain.model.RequestStatus;

import java.util.Map;
import java.util.Objects;

import static com.example.requestmanagement.domain.model.RequestEvent.ACCEPT;
import static com.example.requestmanagement.domain.model.RequestEvent.DELETE;
import static com.example.requestmanagement.domain.model.RequestEvent.PUBLISH;
import static com.example.requestmanagement.domain.model.RequestEvent.REJECT;
import static com.example.requestmanagement.domain.model.RequestEvent.VERIFY;
import static com.example.requestmanagement.domain.model.RequestStatus.ACCEPTED;
import static com.example.requestmanagement.domain.model.RequestStatus.CREATED;
import static com.example.requestmanagement.domain.model.RequestStatus.DELETED;
import static com.example.requestmanagement.domain.model.RequestStatus.PUBLISHED;
import static com.example.requestmanagement.domain.model.RequestStatus.REJECTED;
import static com.example.requestmanagement.domain.model.RequestStatus.VERIFIED;

public class DefaultRequestStateMachine implements RequestStateMachine {

    private record TransitionKey(RequestStatus state, RequestEvent event) {
    }

    private static final Map<TransitionKey, RequestStatus> TRANSITIONS = Map.of(
            new TransitionKey(CREATED, VERIFY), VERIFIED,
            new TransitionKey(CREATED, DELETE), DELETED,
            new TransitionKey(VERIFIED, ACCEPT), ACCEPTED,
            new TransitionKey(VERIFIED, REJECT), REJECTED,
            new TransitionKey(ACCEPTED, REJECT), REJECTED,
            new TransitionKey(ACCEPTED, PUBLISH), PUBLISHED
    );

    @Override
    public RequestStatus transition(RequestStatus currentState, RequestEvent event) {
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(event, "event");
        RequestStatus next = TRANSITIONS.get(new TransitionKey(currentState, event));
        if (next == null) {
            throw new InvalidTransitionException(currentState, event);
        }
        return next;
    }
}
