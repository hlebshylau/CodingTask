package com.example.requestmanagement.application.service;

import com.example.requestmanagement.application.port.ActorProvider;
import com.example.requestmanagement.application.port.AuditRepository;
import com.example.requestmanagement.application.port.PageResult;
import com.example.requestmanagement.application.port.PublicationIdGenerator;
import com.example.requestmanagement.application.port.RequestRepository;
import com.example.requestmanagement.application.port.RequestSearchCriteria;
import com.example.requestmanagement.domain.exception.InvalidTransitionException;
import com.example.requestmanagement.domain.exception.MissingReasonException;
import com.example.requestmanagement.domain.exception.RequestNotEditableException;
import com.example.requestmanagement.domain.exception.RequestNotFoundException;
import com.example.requestmanagement.domain.model.AuditEntry;
import com.example.requestmanagement.domain.model.Request;
import com.example.requestmanagement.domain.model.RequestEvent;
import com.example.requestmanagement.domain.model.RequestStatus;
import com.example.requestmanagement.domain.statemachine.DefaultRequestStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-10-19T10:00:00.123456Z");
    private static final Instant EARLIER = Instant.parse("2026-10-18T10:00:00Z");

    @Mock
    private RequestRepository requests;
    @Mock
    private AuditRepository audit;
    @Mock
    private PublicationIdGenerator publicationIds;
    @Mock
    private ActorProvider actorProvider;

    private RequestService service;

    @BeforeEach
    void setUp() {
        service = new RequestService(requests, audit, publicationIds, new DefaultRequestStateMachine(),
                actorProvider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsRequestWithCreatedStatusAndTimestamps() {
        when(requests.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));

        Request created = service.create("Loan", "Body");

        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(requests).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RequestStatus.CREATED);
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW_MICROS);
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(NOW_MICROS);
        assertThat(created.getId()).isEqualTo(1L);
        verifyNoInteractions(audit);
    }

    @Test
    void getActiveReturnsExistingRequest() {
        when(requests.findById(1L)).thenReturn(Optional.of(stored(RequestStatus.VERIFIED)));

        assertThat(service.getActive(1L).getStatus()).isEqualTo(RequestStatus.VERIFIED);
    }

    @Test
    void getActiveTreatsDeletedRequestAsMissing() {
        when(requests.findById(1L)).thenReturn(Optional.of(stored(RequestStatus.DELETED)));

        assertThatThrownBy(() -> service.getActive(1L)).isInstanceOf(RequestNotFoundException.class);
    }

    @Test
    void getActiveFailsForUnknownRequest() {
        when(requests.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getActive(99L))
                .isInstanceOf(RequestNotFoundException.class)
                .hasMessage("Request 99 not found");
    }

    @Test
    void updatesBodyOfEditableRequest() {
        when(requests.findById(1L)).thenReturn(Optional.of(stored(RequestStatus.VERIFIED)));
        when(requests.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Request updated = service.updateBody(1L, "New body");

        assertThat(updated.getBody()).isEqualTo("New body");
        assertThat(updated.getUpdatedAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void doesNotSaveBodyOfLockedRequest() {
        when(requests.findById(1L)).thenReturn(Optional.of(stored(RequestStatus.ACCEPTED)));

        assertThatThrownBy(() -> service.updateBody(1L, "New body")).isInstanceOf(RequestNotEditableException.class);
        verify(requests, never()).save(any());
    }

    @Test
    void updateBodyFailsForUnknownRequest() {
        when(requests.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateBody(1L, "New body")).isInstanceOf(RequestNotFoundException.class);
    }

    @Test
    void appliesEventAndRecordsAuditEntry() {
        when(requests.findById(1L)).thenReturn(Optional.of(stored(RequestStatus.VERIFIED)));
        when(requests.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(actorProvider.currentActor()).thenReturn("SYSTEM");

        Request result = service.applyEvent(1L, RequestEvent.REJECT, "Missing documents");

        assertThat(result.getStatus()).isEqualTo(RequestStatus.REJECTED);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new AuditEntry(null, 1L, RequestStatus.VERIFIED,
                RequestStatus.REJECTED, RequestEvent.REJECT, "Missing documents", "SYSTEM", NOW_MICROS));
        verifyNoInteractions(publicationIds);
    }

    @Test
    void publishingUsesGeneratedPublicationId() {
        when(requests.findById(1L)).thenReturn(Optional.of(stored(RequestStatus.ACCEPTED)));
        when(requests.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicationIds.nextId()).thenReturn(1000L);
        when(actorProvider.currentActor()).thenReturn("SYSTEM");

        Request result = service.applyEvent(1L, RequestEvent.PUBLISH, null);

        assertThat(result.getStatus()).isEqualTo(RequestStatus.PUBLISHED);
        assertThat(result.getPublicationId()).isEqualTo(1000L);
    }

    @Test
    void invalidTransitionPersistsNothing() {
        when(requests.findById(1L)).thenReturn(Optional.of(stored(RequestStatus.CREATED)));

        assertThatThrownBy(() -> service.applyEvent(1L, RequestEvent.PUBLISH, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessage("Cannot apply PUBLISH event in CREATED state");

        verify(requests, never()).save(any());
        verifyNoInteractions(audit, publicationIds);
    }

    @Test
    void missingReasonPersistsNothing() {
        when(requests.findById(1L)).thenReturn(Optional.of(stored(RequestStatus.CREATED)));

        assertThatThrownBy(() -> service.applyEvent(1L, RequestEvent.DELETE, " "))
                .isInstanceOf(MissingReasonException.class);

        verify(requests, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void applyEventFailsForUnknownRequest() {
        when(requests.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyEvent(5L, RequestEvent.VERIFY, null))
                .isInstanceOf(RequestNotFoundException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void searchDelegatesToRepository() {
        RequestSearchCriteria criteria = new RequestSearchCriteria("loan", RequestStatus.VERIFIED, 0, 10);
        PageResult<Request> page = new PageResult<>(List.of(stored(RequestStatus.VERIFIED)), 0, 10, 1, 1);
        when(requests.search(criteria)).thenReturn(page);

        assertThat(service.search(criteria)).isSameAs(page);
    }

    @Test
    void auditTrailIsAvailableForExistingRequest() {
        AuditEntry entry = new AuditEntry(1L, 1L, RequestStatus.CREATED, RequestStatus.DELETED,
                RequestEvent.DELETE, "Mistake", "SYSTEM", NOW);
        when(requests.existsById(1L)).thenReturn(true);
        when(audit.findByRequestIdChronologically(1L)).thenReturn(List.of(entry));

        assertThat(service.auditTrail(1L)).containsExactly(entry);
    }

    @Test
    void auditTrailFailsForUnknownRequest() {
        when(requests.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.auditTrail(1L)).isInstanceOf(RequestNotFoundException.class);
        verifyNoInteractions(audit);
    }

    private static Request stored(RequestStatus status) {
        Long publicationId = status == RequestStatus.PUBLISHED ? 1000L : null;
        return Request.restore(1L, "Loan", "Body", status, publicationId, EARLIER, EARLIER, 0L);
    }

    private static Request withId(Request request, Long id) {
        return Request.restore(id, request.getName(), request.getBody(), request.getStatus(),
                request.getPublicationId(), request.getCreatedAt(), request.getUpdatedAt(), 0L);
    }
}
