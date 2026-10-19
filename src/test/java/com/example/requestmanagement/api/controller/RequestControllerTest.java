package com.example.requestmanagement.api.controller;

import com.example.requestmanagement.application.port.PageResult;
import com.example.requestmanagement.application.port.RequestSearchCriteria;
import com.example.requestmanagement.application.service.RequestService;
import com.example.requestmanagement.domain.exception.InvalidTransitionException;
import com.example.requestmanagement.domain.exception.MissingReasonException;
import com.example.requestmanagement.domain.exception.RequestNotEditableException;
import com.example.requestmanagement.domain.exception.RequestNotFoundException;
import com.example.requestmanagement.domain.model.AuditEntry;
import com.example.requestmanagement.domain.model.Request;
import com.example.requestmanagement.domain.model.RequestEvent;
import com.example.requestmanagement.domain.model.RequestStatus;
import com.example.requestmanagement.infrastructure.persistence.entity.RequestEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RequestController.class)
class RequestControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-19T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RequestService requestService;

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        when(requestService.create("Loan application", "Customer requests a personal loan"))
                .thenReturn(request(1L, RequestStatus.CREATED, null));

        mockMvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Loan application", "body": "Customer requests a personal loan"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/requests/1")))
                .andExpect(content().json("""
                        {
                          "id": 1,
                          "name": "Loan application",
                          "body": "Customer requests a personal loan",
                          "status": "CREATED",
                          "publicationId": null,
                          "createdAt": "2026-10-19T10:00:00Z",
                          "updatedAt": "2026-10-19T10:00:00Z"
                        }""", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"body\": \"Body\"}",
            "{\"name\": \"\", \"body\": \"Body\"}",
            "{\"name\": \"   \", \"body\": \"Body\"}",
            "{\"name\": \"Name\"}",
            "{\"name\": \"Name\", \"body\": \"  \"}"
    })
    void createRejectsMissingOrBlankFields(String json) throws Exception {
        mockMvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("must not be blank")))
                .andExpect(jsonPath("$.timestamp").exists());
        verifyNoInteractions(requestService);
    }

    @Test
    void createRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void createRejectsTooLongName() throws Exception {
        String name = "x".repeat(256);
        mockMvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\", \"body\": \"Body\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("name")));
    }

    @Test
    void getReturnsRequest() throws Exception {
        when(requestService.getActive(1L)).thenReturn(request(1L, RequestStatus.PUBLISHED, 1000L));

        mockMvc.perform(get("/api/requests/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publicationId").value(1000))
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void getReturns404ForMissingRequest() throws Exception {
        when(requestService.getActive(7L)).thenThrow(new RequestNotFoundException(7L));

        mockMvc.perform(get("/api/requests/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Request 7 not found"));
    }

    @Test
    void getRejectsNonNumericId() throws Exception {
        mockMvc.perform(get("/api/requests/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value 'abc' for parameter 'id'"));
    }

    @Test
    void searchUsesDefaultsAndReturnsPage() throws Exception {
        when(requestService.search(new RequestSearchCriteria(null, null, 0, 10)))
                .thenReturn(new PageResult<>(List.of(request(1L, RequestStatus.CREATED, null)), 0, 10, 1, 1));

        mockMvc.perform(get("/api/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void searchPassesFilters() throws Exception {
        RequestSearchCriteria expected = new RequestSearchCriteria("loan", RequestStatus.DELETED, 2, 5);
        when(requestService.search(expected)).thenReturn(new PageResult<>(List.of(), 2, 5, 0, 0));

        mockMvc.perform(get("/api/requests?page=2&size=5&name=loan&status=DELETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        verify(requestService).search(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=x", "size=ten"})
    void searchRejectsInvalidPagination(String query) throws Exception {
        mockMvc.perform(get("/api/requests?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        verifyNoInteractions(requestService);
    }

    @Test
    void searchRejectsUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/requests?status=ARCHIVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Allowed values")));
    }

    @Test
    void updateBodyReturnsUpdatedRequest() throws Exception {
        when(requestService.updateBody(1L, "Updated")).thenReturn(request(1L, RequestStatus.CREATED, null));

        mockMvc.perform(patch("/api/requests/1").contentType(MediaType.APPLICATION_JSON).content("""
                        {"body": "Updated"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void updateBodyRejectsBlankBody() throws Exception {
        mockMvc.perform(patch("/api/requests/1").contentType(MediaType.APPLICATION_JSON).content("""
                        {"body": " "}"""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(requestService);
    }

    @Test
    void updateBodyReturns409ForLockedRequest() throws Exception {
        when(requestService.updateBody(1L, "Updated"))
                .thenThrow(new RequestNotEditableException(1L, RequestStatus.ACCEPTED));

        mockMvc.perform(patch("/api/requests/1").contentType(MediaType.APPLICATION_JSON).content("""
                        {"body": "Updated"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Request 1 cannot be modified in ACCEPTED state"));
    }

    @Test
    void updateBodyReturns404ForMissingRequest() throws Exception {
        when(requestService.updateBody(9L, "Updated")).thenThrow(new RequestNotFoundException(9L));

        mockMvc.perform(patch("/api/requests/9").contentType(MediaType.APPLICATION_JSON).content("""
                        {"body": "Updated"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void applyEventReturnsUpdatedRequest() throws Exception {
        when(requestService.applyEvent(1L, RequestEvent.REJECT, "Does not meet requirements"))
                .thenReturn(request(1L, RequestStatus.REJECTED, null));

        mockMvc.perform(post("/api/requests/1/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"event": "REJECT", "reason": "Does not meet requirements"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void applyEventReturns409ForInvalidTransition() throws Exception {
        when(requestService.applyEvent(1L, RequestEvent.PUBLISH, null))
                .thenThrow(new InvalidTransitionException(RequestStatus.CREATED, RequestEvent.PUBLISH));

        mockMvc.perform(post("/api/requests/1/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"event": "PUBLISH"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Cannot apply PUBLISH event in CREATED state"));
    }

    @Test
    void applyEventReturns400ForMissingReason() throws Exception {
        when(requestService.applyEvent(1L, RequestEvent.DELETE, null))
                .thenThrow(new MissingReasonException(RequestEvent.DELETE));

        mockMvc.perform(post("/api/requests/1/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"event": "DELETE"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A non-blank reason is required for DELETE event"));
    }

    @Test
    void applyEventReturns400ForUnsupportedEvent() throws Exception {
        mockMvc.perform(post("/api/requests/1/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"event": "ARCHIVE"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Unsupported event 'ARCHIVE'. Allowed values: [VERIFY, ACCEPT, PUBLISH, REJECT, DELETE]"));
        verifyNoInteractions(requestService);
    }

    @Test
    void applyEventReturns400ForMissingEvent() throws Exception {
        mockMvc.perform(post("/api/requests/1/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("event: must not be null"));
    }

    @Test
    void applyEventReturns409ForOptimisticLockConflict() throws Exception {
        when(requestService.applyEvent(1L, RequestEvent.ACCEPT, null))
                .thenThrow(new ObjectOptimisticLockingFailureException(RequestEntity.class, 1L));

        mockMvc.perform(post("/api/requests/1/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"event": "ACCEPT"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The request was modified concurrently, reload it and try again"));
    }

    @Test
    void auditReturnsEntries() throws Exception {
        when(requestService.auditTrail(1L)).thenReturn(List.of(new AuditEntry(
                5L, 1L, RequestStatus.CREATED, RequestStatus.DELETED, RequestEvent.DELETE, "Mistake", "SYSTEM", T0)));

        mockMvc.perform(get("/api/requests/1/audit"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{
                          "id": 5,
                          "requestId": 1,
                          "previousStatus": "CREATED",
                          "newStatus": "DELETED",
                          "event": "DELETE",
                          "reason": "Mistake",
                          "performedBy": "SYSTEM",
                          "timestamp": "2026-10-19T10:00:00Z"
                        }]""", true));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        when(requestService.getActive(any())).thenThrow(new IllegalStateException("db password is secret"));

        mockMvc.perform(get("/api/requests/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    private static Request request(Long id, RequestStatus status, Long publicationId) {
        return Request.restore(id, "Loan application", "Customer requests a personal loan", status, publicationId,
                T0, T0, 0L);
    }
}
