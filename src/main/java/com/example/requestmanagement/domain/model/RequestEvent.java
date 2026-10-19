package com.example.requestmanagement.domain.model;

public enum RequestEvent {
    VERIFY,
    ACCEPT,
    PUBLISH,
    REJECT,
    DELETE;

    public boolean requiresReason() {
        return this == REJECT || this == DELETE;
    }
}
