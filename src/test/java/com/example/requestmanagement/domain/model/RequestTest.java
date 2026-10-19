package com.example.requestmanagement.domain.model;

import com.example.requestmanagement.domain.exception.DomainValidationException;
import com.example.requestmanagement.domain.exception.InvalidTransitionException;
import com.example.requestmanagement.domain.exception.MissingReasonException;
import com.example.requestmanagement.domain.exception.RequestNotEditableException;
import com.example.requestmanagement.domain.statemachine.DefaultRequestStateMachine;
import com.example.requestmanagement.domain.statemachine.RequestStateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-19T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-19T11:00:00Z");
    private static final LongSupplier NO_PUBLICATION_ID = () -> {
        throw new AssertionError("Publication id must not be generated");
    };

    private final RequestStateMachine stateMachine = new DefaultRequestStateMachine();

    @Test
    void createsRequestInCreatedState() {
        Request request = Request.create("Loan application", "Personal loan", CREATED_AT);

        assertThat(request.getId()).isNull();
        assertThat(request.getName()).isEqualTo("Loan application");
        assertThat(request.getBody()).isEqualTo("Personal loan");
        assertThat(request.getStatus()).isEqualTo(RequestStatus.CREATED);
        assertThat(request.getPublicationId()).isNull();
        assertThat(request.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(request.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsBlankName(String name) {
        assertThatThrownBy(() -> Request.create(name, "body", CREATED_AT))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("name must not be blank");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsBlankBody(String body) {
        assertThatThrownBy(() -> Request.create("name", body, CREATED_AT))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("body must not be blank");
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"CREATED", "VERIFIED"})
    void updatesBodyInEditableStates(RequestStatus status) {
        Request request = requestIn(status);

        request.updateBody("New body", LATER);

        assertThat(request.getBody()).isEqualTo("New body");
        assertThat(request.getUpdatedAt()).isEqualTo(LATER);
        assertThat(request.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"ACCEPTED", "PUBLISHED", "REJECTED", "DELETED"})
    void refusesBodyUpdateInLockedStates(RequestStatus status) {
        Request request = requestIn(status);

        assertThatThrownBy(() -> request.updateBody("New body", LATER))
                .isInstanceOf(RequestNotEditableException.class)
                .hasMessageContaining(status.name());
        assertThat(request.getBody()).isEqualTo("Body");
        assertThat(request.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void refusesBlankBodyUpdate() {
        Request request = requestIn(RequestStatus.CREATED);

        assertThatThrownBy(() -> request.updateBody(" ", LATER)).isInstanceOf(DomainValidationException.class);
        assertThat(request.getBody()).isEqualTo("Body");
    }

    @Test
    void appliesTransitionAndDescribesIt() {
        Request request = requestIn(RequestStatus.CREATED);

        StatusTransition transition = request.apply(RequestEvent.VERIFY, null, stateMachine, NO_PUBLICATION_ID, LATER);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.VERIFIED);
        assertThat(request.getUpdatedAt()).isEqualTo(LATER);
        assertThat(transition).isEqualTo(new StatusTransition(
                1L, RequestStatus.CREATED, RequestStatus.VERIFIED, RequestEvent.VERIFY, null, LATER));
    }

    @Test
    void publishingAssignsPublicationId() {
        Request request = requestIn(RequestStatus.ACCEPTED);

        request.apply(RequestEvent.PUBLISH, null, stateMachine, () -> 1000L, LATER);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PUBLISHED);
        assertThat(request.getPublicationId()).isEqualTo(1000L);
    }

    @Test
    void publishingRejectsNonPositivePublicationId() {
        Request request = requestIn(RequestStatus.ACCEPTED);

        assertThatThrownBy(() -> request.apply(RequestEvent.PUBLISH, null, stateMachine, () -> 0L, LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.ACCEPTED);
        assertThat(request.getPublicationId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"CREATED", "VERIFIED", "PUBLISHED", "REJECTED", "DELETED"})
    void publicationIdIsNotGeneratedForInvalidPublish(RequestStatus status) {
        AtomicInteger calls = new AtomicInteger();
        Request request = requestIn(status);

        assertThatThrownBy(() -> request.apply(RequestEvent.PUBLISH, null, stateMachine,
                () -> calls.incrementAndGet(), LATER))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(calls).hasValue(0);
        assertThat(request.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void rejectionRequiresReason(String reason) {
        Request request = requestIn(RequestStatus.VERIFIED);

        assertThatThrownBy(() -> request.apply(RequestEvent.REJECT, reason, stateMachine, NO_PUBLICATION_ID, LATER))
                .isInstanceOf(MissingReasonException.class)
                .hasMessage("A non-blank reason is required for REJECT event");
        assertThat(request.getStatus()).isEqualTo(RequestStatus.VERIFIED);
        assertThat(request.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void deletionRequiresReason(String reason) {
        Request request = requestIn(RequestStatus.CREATED);

        assertThatThrownBy(() -> request.apply(RequestEvent.DELETE, reason, stateMachine, NO_PUBLICATION_ID, LATER))
                .isInstanceOf(MissingReasonException.class);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.CREATED);
    }

    @Test
    void stateIsValidatedBeforeBusinessGuards() {
        Request request = requestIn(RequestStatus.CREATED);

        assertThatThrownBy(() -> request.apply(RequestEvent.REJECT, null, stateMachine, NO_PUBLICATION_ID, LATER))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void transitionCarriesStrippedReason() {
        Request request = requestIn(RequestStatus.ACCEPTED);

        StatusTransition transition = request.apply(
                RequestEvent.REJECT, "  Missing documents ", stateMachine, NO_PUBLICATION_ID, LATER);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(transition.reason()).isEqualTo("Missing documents");
    }

    private static Request requestIn(RequestStatus status) {
        Long publicationId = status == RequestStatus.PUBLISHED ? 1000L : null;
        return Request.restore(1L, "Name", "Body", status, publicationId, CREATED_AT, CREATED_AT, 0L);
    }
}
