package com.example.requestmanagement.domain.model;

import java.util.EnumSet;
import java.util.Set;

public enum RequestStatus {
    CREATED,
    VERIFIED,
    ACCEPTED,
    PUBLISHED,
    REJECTED,
    DELETED;

    private static final Set<RequestStatus> EDITABLE = EnumSet.of(CREATED, VERIFIED);
    private static final Set<RequestStatus> TERMINAL = EnumSet.of(PUBLISHED, REJECTED, DELETED);

    public boolean isEditable() {
        return EDITABLE.contains(this);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
