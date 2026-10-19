package com.example.requestmanagement.domain.statemachine;

import com.example.requestmanagement.domain.model.RequestEvent;
import com.example.requestmanagement.domain.model.RequestStatus;

public interface RequestStateMachine {

    RequestStatus transition(RequestStatus currentState, RequestEvent event);
}
