package com.example.requestmanagement.application.service;

import com.example.requestmanagement.application.port.ActorProvider;
import com.example.requestmanagement.application.port.AuditRepository;
import com.example.requestmanagement.application.port.PageResult;
import com.example.requestmanagement.application.port.PublicationIdGenerator;
import com.example.requestmanagement.application.port.RequestRepository;
import com.example.requestmanagement.application.port.RequestSearchCriteria;
import com.example.requestmanagement.domain.exception.RequestNotFoundException;
import com.example.requestmanagement.domain.model.AuditEntry;
import com.example.requestmanagement.domain.model.Request;
import com.example.requestmanagement.domain.model.RequestEvent;
import com.example.requestmanagement.domain.model.RequestStatus;
import com.example.requestmanagement.domain.model.StatusTransition;
import com.example.requestmanagement.domain.statemachine.RequestStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Transactional
public class RequestService {

    private static final Logger log = LoggerFactory.getLogger(RequestService.class);

    private final RequestRepository requests;
    private final AuditRepository audit;
    private final PublicationIdGenerator publicationIds;
    private final RequestStateMachine stateMachine;
    private final ActorProvider actorProvider;
    private final Clock clock;

    public RequestService(RequestRepository requests, AuditRepository audit, PublicationIdGenerator publicationIds,
                          RequestStateMachine stateMachine, ActorProvider actorProvider, Clock clock) {
        this.requests = requests;
        this.audit = audit;
        this.publicationIds = publicationIds;
        this.stateMachine = stateMachine;
        this.actorProvider = actorProvider;
        this.clock = clock;
    }

    public Request create(String name, String body) {
        Request created = requests.save(Request.create(name, body, now()));
        log.info("Created request {}", created.getId());
        return created;
    }

    @Transactional(readOnly = true)
    public Request getActive(Long id) {
        return requests.findById(id)
                .filter(request -> request.getStatus() != RequestStatus.DELETED)
                .orElseThrow(() -> new RequestNotFoundException(id));
    }

    public Request updateBody(Long id, String body) {
        Request request = load(id);
        request.updateBody(body, now());
        return requests.save(request);
    }

    public Request applyEvent(Long id, RequestEvent event, String reason) {
        Request request = load(id);
        StatusTransition transition = request.apply(event, reason, stateMachine, publicationIds::nextId, now());
        Request saved = requests.save(request);
        audit.save(AuditEntry.of(transition, actorProvider.currentActor()));
        log.info("Request {} moved from {} to {} by {}",
                id, transition.previousStatus(), transition.newStatus(), event);
        return saved;
    }

    @Transactional(readOnly = true)
    public PageResult<Request> search(RequestSearchCriteria criteria) {
        return requests.search(criteria);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> auditTrail(Long id) {
        if (!requests.existsById(id)) {
            throw new RequestNotFoundException(id);
        }
        return audit.findByRequestIdChronologically(id);
    }

    private Request load(Long id) {
        return requests.findById(id).orElseThrow(() -> new RequestNotFoundException(id));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
