package com.example.requestmanagement.domain.statemachine;

import com.example.requestmanagement.domain.exception.MissingReasonException;
import com.example.requestmanagement.domain.model.RequestEvent;

public final class TransitionGuards {

    private TransitionGuards() {
    }

    public static void check(RequestEvent event, String reason) {
        if (event.requiresReason() && (reason == null || reason.isBlank())) {
            throw new MissingReasonException(event);
        }
    }

    public static long requireValidPublicationId(long publicationId) {
        if (publicationId <= 0) {
            throw new IllegalStateException("Publication identifier must be positive, got " + publicationId);
        }
        return publicationId;
    }
}
