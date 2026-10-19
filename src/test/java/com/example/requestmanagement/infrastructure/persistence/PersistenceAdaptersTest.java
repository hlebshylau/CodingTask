package com.example.requestmanagement.infrastructure.persistence;

import com.example.requestmanagement.application.port.PageResult;
import com.example.requestmanagement.application.port.RequestSearchCriteria;
import com.example.requestmanagement.domain.model.AuditEntry;
import com.example.requestmanagement.domain.model.Request;
import com.example.requestmanagement.domain.model.RequestEvent;
import com.example.requestmanagement.domain.model.RequestStatus;
import com.example.requestmanagement.infrastructure.persistence.adapter.AuditPersistenceAdapter;
import com.example.requestmanagement.infrastructure.persistence.adapter.RequestPersistenceAdapter;
import com.example.requestmanagement.infrastructure.persistence.adapter.SequencePublicationIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({RequestPersistenceAdapter.class, AuditPersistenceAdapter.class, SequencePublicationIdGenerator.class})
class PersistenceAdaptersTest {

    private static final Instant T0 = Instant.parse("2026-10-19T10:00:00Z");

    @Autowired
    private RequestPersistenceAdapter requests;
    @Autowired
    private AuditPersistenceAdapter audit;
    @Autowired
    private SequencePublicationIdGenerator publicationIds;
    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void savesAndLoadsRequest() {
        Request saved = requests.save(Request.create("Loan", "Body", T0));

        Request loaded = requests.findById(saved.getId()).orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isZero();
        assertThat(loaded.getName()).isEqualTo("Loan");
        assertThat(loaded.getBody()).isEqualTo("Body");
        assertThat(loaded.getStatus()).isEqualTo(RequestStatus.CREATED);
        assertThat(loaded.getCreatedAt()).isEqualTo(T0);
        assertThat(loaded.getUpdatedAt()).isEqualTo(T0);
        assertThat(requests.existsById(saved.getId())).isTrue();
        assertThat(requests.existsById(-1L)).isFalse();
    }

    @Test
    void updateIncrementsVersion() {
        Request saved = requests.save(Request.create("Loan", "Body", T0));
        saved.updateBody("Changed", T0.plusSeconds(5));

        Request updated = requests.save(saved);

        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getBody()).isEqualTo("Changed");
        assertThat(updated.getUpdatedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void staleVersionIsRejected() {
        Request saved = requests.save(Request.create("Loan", "Body", T0));
        Request first = requests.findById(saved.getId()).orElseThrow();
        Request stale = requests.findById(saved.getId()).orElseThrow();

        first.updateBody("First", T0.plusSeconds(1));
        requests.save(first);
        stale.updateBody("Second", T0.plusSeconds(2));

        assertThatThrownBy(() -> requests.save(stale)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(requests.findById(saved.getId()).orElseThrow().getBody()).isEqualTo("First");
    }

    @Test
    void searchExcludesDeletedByDefault() {
        Request active = saveWithStatus("Active", RequestStatus.CREATED, T0);
        Request deleted = saveWithStatus("Gone", RequestStatus.DELETED, T0.plusSeconds(1));

        assertThat(ids(requests.search(criteria(null, null, 0, 10)))).containsExactly(active.getId());
        assertThat(ids(requests.search(criteria(null, RequestStatus.DELETED, 0, 10))))
                .containsExactly(deleted.getId());
    }

    @Test
    void searchFiltersByNameCaseInsensitiveSubstring() {
        Request loan = saveWithStatus("Personal LOAN application", RequestStatus.CREATED, T0);
        saveWithStatus("Mortgage", RequestStatus.CREATED, T0);

        assertThat(ids(requests.search(criteria("loan", null, 0, 10)))).containsExactly(loan.getId());
        assertThat(ids(requests.search(criteria("Al Lo", null, 0, 10)))).containsExactly(loan.getId());
    }

    @Test
    void searchTreatsLikeWildcardsLiterally() {
        Request percent = saveWithStatus("100% done", RequestStatus.CREATED, T0);
        saveWithStatus("100 done", RequestStatus.CREATED, T0);

        assertThat(ids(requests.search(criteria("100%", null, 0, 10)))).containsExactly(percent.getId());
        assertThat(requests.search(criteria("_", null, 0, 10)).content()).isEmpty();
    }

    @Test
    void searchFiltersByStatusAndName() {
        saveWithStatus("Loan one", RequestStatus.CREATED, T0);
        Request verified = saveWithStatus("Loan two", RequestStatus.VERIFIED, T0);
        saveWithStatus("Other", RequestStatus.VERIFIED, T0);

        assertThat(ids(requests.search(criteria("loan", RequestStatus.VERIFIED, 0, 10))))
                .containsExactly(verified.getId());
    }

    @Test
    void searchPaginatesWithDeterministicOrder() {
        Request a = saveWithStatus("A", RequestStatus.CREATED, T0);
        Request b = saveWithStatus("B", RequestStatus.CREATED, T0);
        Request c = saveWithStatus("C", RequestStatus.CREATED, T0.plusSeconds(10));

        PageResult<Request> first = requests.search(criteria(null, null, 0, 2));
        PageResult<Request> second = requests.search(criteria(null, null, 1, 2));

        assertThat(ids(first)).containsExactly(c.getId(), b.getId());
        assertThat(ids(second)).containsExactly(a.getId());
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
    }

    @Test
    void publicationIdsComeFromSequenceAndAreUnique() {
        Set<Long> generated = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            generated.add(publicationIds.nextId());
        }

        assertThat(generated).hasSize(50).allMatch(id -> id >= 1000);
    }

    @Test
    void databaseRejectsDuplicatePublicationId() {
        saveWithStatus("First", RequestStatus.PUBLISHED, T0, 5000L);

        assertThatThrownBy(() -> saveWithStatus("Second", RequestStatus.PUBLISHED, T0, 5000L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsPublishedRequestWithoutPublicationId() {
        assertThatThrownBy(() -> insert("Broken", RequestStatus.PUBLISHED, T0, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditEntriesAreReturnedChronologically() {
        Request request = requests.save(Request.create("Loan", "Body", T0));
        audit.save(entry(request.getId(), RequestStatus.VERIFIED, RequestStatus.ACCEPTED, RequestEvent.ACCEPT,
                null, T0.plusSeconds(20)));
        audit.save(entry(request.getId(), RequestStatus.CREATED, RequestStatus.VERIFIED, RequestEvent.VERIFY,
                null, T0.plusSeconds(10)));
        audit.save(entry(request.getId(), RequestStatus.ACCEPTED, RequestStatus.REJECTED, RequestEvent.REJECT,
                "Missing documents", T0.plusSeconds(30)));

        List<AuditEntry> trail = audit.findByRequestIdChronologically(request.getId());

        assertThat(trail).extracting(AuditEntry::event)
                .containsExactly(RequestEvent.VERIFY, RequestEvent.ACCEPT, RequestEvent.REJECT);
        assertThat(trail.getLast().reason()).isEqualTo("Missing documents");
        assertThat(trail).allSatisfy(e -> {
            assertThat(e.id()).isNotNull();
            assertThat(e.performedBy()).isEqualTo("SYSTEM");
        });
    }

    @Test
    void auditRequiresExistingRequest() {
        assertThatThrownBy(() -> audit.save(entry(-1L, RequestStatus.CREATED, RequestStatus.VERIFIED,
                RequestEvent.VERIFY, null, T0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Request saveWithStatus(String name, RequestStatus status, Instant createdAt) {
        return saveWithStatus(name, status, createdAt, status == RequestStatus.PUBLISHED ? publicationIds.nextId() : null);
    }

    private Request saveWithStatus(String name, RequestStatus status, Instant createdAt, Long publicationId) {
        Long id = insert(name, status, createdAt, publicationId);
        return requests.findById(id).orElseThrow();
    }

    private Long insert(String name, RequestStatus status, Instant createdAt, Long publicationId) {
        jdbcClient.sql("""
                        INSERT INTO requests (name, body, status, publication_id, created_at, updated_at, version)
                        VALUES (?, 'Body', ?, ?, ?, ?, 0)""")
                .params(name, status.name(), publicationId, createdAt.atOffset(ZoneOffset.UTC),
                        createdAt.atOffset(ZoneOffset.UTC))
                .update();
        return jdbcClient.sql("SELECT MAX(id) FROM requests").query(Long.class).single();
    }

    private static RequestSearchCriteria criteria(String name, RequestStatus status, int page, int size) {
        return new RequestSearchCriteria(name, status, page, size);
    }

    private static List<Long> ids(PageResult<Request> page) {
        return page.content().stream().map(Request::getId).toList();
    }

    private static AuditEntry entry(Long requestId, RequestStatus from, RequestStatus to, RequestEvent event,
                                    String reason, Instant at) {
        return new AuditEntry(null, requestId, from, to, event, reason, "SYSTEM", at);
    }
}
