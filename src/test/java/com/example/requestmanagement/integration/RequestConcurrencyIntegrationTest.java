package com.example.requestmanagement.integration;

import com.example.requestmanagement.application.port.AuditRepository;
import com.example.requestmanagement.application.port.RequestRepository;
import com.example.requestmanagement.application.service.RequestService;
import com.example.requestmanagement.domain.exception.InvalidTransitionException;
import com.example.requestmanagement.domain.model.Request;
import com.example.requestmanagement.domain.model.RequestEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RequestService requestService;

    @MockitoSpyBean
    private RequestRepository requestRepository;

    @MockitoSpyBean
    private AuditRepository auditRepository;

    @AfterEach
    void resetSpies() {
        Mockito.reset(requestRepository, auditRepository);
    }

    @Test
    void conflictingTransitionsOnSameSnapshotAllowOnlyOneWinner() throws Exception {
        long id = createRequestIn("VERIFIED");
        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object loaded = invocation.callRealMethod();
            bothLoaded.await(10, TimeUnit.SECONDS);
            return loaded;
        }).when(requestRepository).findById(eq(id));

        List<Outcome> outcomes = runConcurrently(
                () -> requestService.applyEvent(id, RequestEvent.ACCEPT, null),
                () -> requestService.applyEvent(id, RequestEvent.REJECT, "Concurrent rejection"));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .satisfies(outcome -> assertThat(outcome.error()).isInstanceOf(ConcurrencyFailureException.class));
        assertAuditMatchesFinalState(id);
    }

    @RepeatedTest(10)
    void racingTransitionsNeverRecordBothOutcomes() throws Exception {
        long id = createRequestIn("VERIFIED");

        List<Outcome> outcomes = runConcurrently(
                () -> requestService.applyEvent(id, RequestEvent.ACCEPT, null),
                () -> requestService.applyEvent(id, RequestEvent.REJECT, "Concurrent rejection"));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .satisfies(outcome -> assertThat(outcome.error())
                        .isInstanceOfAny(ConcurrencyFailureException.class, InvalidTransitionException.class));
        assertAuditMatchesFinalState(id);
    }

    @Test
    void concurrentPublicationsProduceDistinctIdentifiers() throws Exception {
        int count = 20;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(createRequestIn("ACCEPTED"));
        }

        List<Callable<Request>> publications = ids.stream()
                .<Callable<Request>>map(id -> () -> requestService.applyEvent(id, RequestEvent.PUBLISH, null))
                .toList();
        List<Outcome> outcomes = runConcurrently(publications);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        List<Long> publicationIds = jdbcClient.sql("SELECT publication_id FROM requests WHERE status = 'PUBLISHED'")
                .query(Long.class).list();
        assertThat(publicationIds).hasSize(count).doesNotHaveDuplicates().doesNotContainNull();
    }

    @Test
    void auditFailureRollsBackStateChange() throws Exception {
        long id = createRequest("Loan", "Body");
        doThrow(new IllegalStateException("audit storage unavailable")).when(auditRepository).save(any());

        sendEvent(id, "VERIFY")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));

        assertThat(statusInDatabase(id)).isEqualTo("CREATED");
        assertThat(jdbcClient.sql("SELECT version FROM requests WHERE id = ?").param(id).query(Long.class).single())
                .isZero();
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void failedPublicationDoesNotKeepPublicationId() throws Exception {
        long id = createRequestIn("ACCEPTED");
        int auditBefore = auditCount(id);
        doThrow(new IllegalStateException("audit storage unavailable")).when(auditRepository).save(any());

        sendEvent(id, "PUBLISH").andExpect(status().isInternalServerError());

        assertThat(statusInDatabase(id)).isEqualTo("ACCEPTED");
        assertThat(jdbcClient.sql("SELECT publication_id FROM requests WHERE id = ?")
                .param(id).query(Long.class).optional()).isEqualTo(Optional.empty());
        assertThat(auditCount(id)).isEqualTo(auditBefore);
    }

    private void assertAuditMatchesFinalState(long id) {
        String finalStatus = statusInDatabase(id);
        List<String> recordedStatuses = jdbcClient.sql(
                        "SELECT new_status FROM request_audit WHERE request_id = ? ORDER BY occurred_at, id")
                .param(id).query(String.class).list();
        assertThat(recordedStatuses).containsExactly("VERIFIED", finalStatus);
        assertThat(finalStatus).isIn("ACCEPTED", "REJECTED");
    }

    @SafeVarargs
    private List<Outcome> runConcurrently(Callable<Request>... tasks) throws Exception {
        return runConcurrently(List.of(tasks));
    }

    private List<Outcome> runConcurrently(List<Callable<Request>> tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.size());
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<Request>> futures = new ArrayList<>();
            for (Callable<Request> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Request> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    outcomes.add(new Outcome(null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome(e.getCause()));
                }
            }
            return outcomes;
        }
    }

    private record Outcome(Throwable error) {

        boolean succeeded() {
            return error == null;
        }
    }
}
