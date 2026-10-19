package com.example.requestmanagement.infrastructure.persistence.entity;

import com.example.requestmanagement.domain.model.RequestEvent;
import com.example.requestmanagement.domain.model.RequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "request_audit")
public class AuditEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "previous_status", nullable = false, length = 20)
    private RequestStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "new_status", nullable = false, length = 20)
    private RequestStatus newStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private RequestEvent event;

    @Column(length = 1000)
    private String reason;

    @Column(name = "performed_by", nullable = false, length = 100)
    private String performedBy;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEntryEntity() {
    }

    public AuditEntryEntity(Long requestId, RequestStatus previousStatus, RequestStatus newStatus, RequestEvent event,
                            String reason, String performedBy, Instant occurredAt) {
        this.requestId = requestId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.event = event;
        this.reason = reason;
        this.performedBy = performedBy;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getRequestId() {
        return requestId;
    }

    public RequestStatus getPreviousStatus() {
        return previousStatus;
    }

    public RequestStatus getNewStatus() {
        return newStatus;
    }

    public RequestEvent getEvent() {
        return event;
    }

    public String getReason() {
        return reason;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
