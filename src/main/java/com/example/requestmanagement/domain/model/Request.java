package com.example.requestmanagement.domain.model;

import com.example.requestmanagement.domain.exception.DomainValidationException;
import com.example.requestmanagement.domain.exception.RequestNotEditableException;
import com.example.requestmanagement.domain.statemachine.RequestStateMachine;
import com.example.requestmanagement.domain.statemachine.TransitionGuards;

import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

public class Request {

    private final Long id;
    private final String name;
    private String body;
    private RequestStatus status;
    private Long publicationId;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    private Request(Long id, String name, String body, RequestStatus status, Long publicationId,
                    Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.name = name;
        this.body = body;
        this.status = status;
        this.publicationId = publicationId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Request create(String name, String body, Instant now) {
        requireText(name, "name");
        requireText(body, "body");
        Objects.requireNonNull(now, "now");
        return new Request(null, name, body, RequestStatus.CREATED, null, now, now, null);
    }

    public static Request restore(Long id, String name, String body, RequestStatus status, Long publicationId,
                                  Instant createdAt, Instant updatedAt, Long version) {
        return new Request(id, name, body, status, publicationId, createdAt, updatedAt, version);
    }

    public void updateBody(String newBody, Instant now) {
        if (!status.isEditable()) {
            throw new RequestNotEditableException(id, status);
        }
        requireText(newBody, "body");
        this.body = newBody;
        this.updatedAt = now;
    }

    public StatusTransition apply(RequestEvent event, String reason, RequestStateMachine stateMachine,
                                  LongSupplier publicationIds, Instant now) {
        RequestStatus previous = status;
        RequestStatus next = stateMachine.transition(previous, event);
        TransitionGuards.check(event, reason);

        if (next == RequestStatus.PUBLISHED) {
            publicationId = TransitionGuards.requireValidPublicationId(publicationIds.getAsLong());
        }
        status = next;
        updatedAt = now;
        return new StatusTransition(id, previous, next, event, normalize(reason), now);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
    }

    private static String normalize(String reason) {
        return reason == null || reason.isBlank() ? null : reason.strip();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBody() {
        return body;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public Long getPublicationId() {
        return publicationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
