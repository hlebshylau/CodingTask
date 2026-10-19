package com.example.requestmanagement.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestWorkflowIntegrationTest extends IntegrationTestSupport {

    @Test
    void createsAndPersistsRequest() throws Exception {
        mockMvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Loan application", "body": "Customer requests a personal loan"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/requests/")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.publicationId", nullValue()))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()));

        Map<String, Object> row = jdbcClient.sql("SELECT * FROM requests").query().singleRow();
        assertThat(row.get("NAME")).isEqualTo("Loan application");
        assertThat(row.get("STATUS")).isEqualTo("CREATED");
        assertThat(row.get("CREATED_AT")).isNotNull();
        assertThat(row.get("UPDATED_AT")).isNotNull();
        assertThat(row.get("VERSION")).isEqualTo(0L);
    }

    @Test
    void createdRequestCanBeRetrievedThroughLocation() throws Exception {
        String location = mockMvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Loan", "body": "Body"}"""))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Loan"));
    }

    @Test
    void invalidCreationPersistsNothing() throws Exception {
        mockMvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": " ", "body": "Body"}"""))
                .andExpect(status().isBadRequest());

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM requests").query(Integer.class).single()).isZero();
    }

    @Test
    void successfulWorkflowPublishesRequest() throws Exception {
        long id = createRequest("Loan", "Body");

        sendEvent(id, "VERIFY").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
        sendEvent(id, "ACCEPT").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        sendEvent(id, "PUBLISH").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publicationId").isNumber());

        mockMvc.perform(get("/api/requests/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publicationId", greaterThanOrEqualTo(1000)));

        Long persistedPublicationId = jdbcClient.sql("SELECT publication_id FROM requests WHERE id = ?")
                .param(id).query(Long.class).single();
        assertThat(persistedPublicationId).isNotNull().isPositive();

        mockMvc.perform(get("/api/requests/{id}/audit", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].previousStatus").value("CREATED"))
                .andExpect(jsonPath("$[0].newStatus").value("VERIFIED"))
                .andExpect(jsonPath("$[0].event").value("VERIFY"))
                .andExpect(jsonPath("$[1].previousStatus").value("VERIFIED"))
                .andExpect(jsonPath("$[1].newStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$[1].event").value("ACCEPT"))
                .andExpect(jsonPath("$[2].previousStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$[2].newStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$[2].event").value("PUBLISH"))
                .andExpect(jsonPath("$[*].performedBy").value(everyItem(is("SYSTEM"))))
                .andExpect(jsonPath("$[*].timestamp").value(everyItem(notNullValue())));
    }

    @Test
    void publicationIdsAreUniqueAcrossRequests() throws Exception {
        long first = createRequestIn("PUBLISHED");
        long second = createRequestIn("PUBLISHED");

        Long firstId = publicationId(first);
        Long secondId = publicationId(second);

        assertThat(firstId).isNotNull();
        assertThat(secondId).isNotNull().isNotEqualTo(firstId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "VERIFIED", "PUBLISHED", "REJECTED", "DELETED"})
    void onlyAcceptedRequestsCanBePublished(String state) throws Exception {
        long id = createRequestIn(state);
        int auditBefore = auditCount(id);

        sendEvent(id, "PUBLISH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot apply PUBLISH event in " + state + " state"));

        assertThat(statusInDatabase(id)).isEqualTo(state);
        assertThat(auditCount(id)).isEqualTo(auditBefore);
    }

    @Test
    void invalidWorkflowLeavesRequestUnchanged() throws Exception {
        long id = createRequest("Loan", "Body");
        Map<String, Object> before = jdbcClient.sql("SELECT * FROM requests WHERE id = ?").param(id).query().singleRow();

        sendEvent(id, "PUBLISH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Cannot apply PUBLISH event in CREATED state"))
                .andExpect(jsonPath("$.timestamp").exists());

        Map<String, Object> after = jdbcClient.sql("SELECT * FROM requests WHERE id = ?").param(id).query().singleRow();
        assertThat(after).isEqualTo(before);
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void rejectionWorkflowRecordsReason() throws Exception {
        long id = createRequest("Loan", "Body");
        sendEvent(id, "VERIFY").andExpect(status().isOk());

        sendEvent(id, "REJECT", "Missing required documents")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(get("/api/requests/{id}/audit", id))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].previousStatus").value("VERIFIED"))
                .andExpect(jsonPath("$[1].newStatus").value("REJECTED"))
                .andExpect(jsonPath("$[1].event").value("REJECT"))
                .andExpect(jsonPath("$[1].reason").value("Missing required documents"));
        assertThat(jdbcClient.sql("SELECT reason FROM request_audit WHERE request_id = ? AND event = 'REJECT'")
                .param(id).query(String.class).single()).isEqualTo("Missing required documents");
    }

    @Test
    void acceptedRequestCanBeRejected() throws Exception {
        long id = createRequestIn("ACCEPTED");

        sendEvent(id, "REJECT", "Budget exhausted").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "PUBLISHED", "REJECTED", "DELETED"})
    void rejectionIsRefusedInOtherStates(String state) throws Exception {
        long id = createRequestIn(state);

        sendEvent(id, "REJECT", "Reason").andExpect(status().isConflict());
        assertThat(statusInDatabase(id)).isEqualTo(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECT", "DELETE"})
    void reasonIsMandatory(String event) throws Exception {
        long id = createRequestIn(event.equals("REJECT") ? "VERIFIED" : "CREATED");
        String state = statusInDatabase(id);
        int auditBefore = auditCount(id);

        sendEvent(id, event).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A non-blank reason is required for " + event + " event"));
        sendEvent(id, event, "   ").andExpect(status().isBadRequest());

        assertThat(statusInDatabase(id)).isEqualTo(state);
        assertThat(auditCount(id)).isEqualTo(auditBefore);
    }

    @Test
    void deletionWorkflowSoftDeletesRequest() throws Exception {
        long kept = createRequest("Kept loan", "Body");
        long deleted = createRequest("Deleted loan", "Body");

        sendEvent(deleted, "DELETE", "Created by mistake")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));

        assertThat(statusInDatabase(deleted)).isEqualTo("DELETED");

        mockMvc.perform(get("/api/requests"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(kept));
        mockMvc.perform(get("/api/requests?name=loan"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/requests?status=DELETED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(deleted))
                .andExpect(jsonPath("$.content[0].status").value("DELETED"));
        mockMvc.perform(get("/api/requests/{id}", deleted))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/requests/{id}/audit", deleted))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event").value("DELETE"))
                .andExpect(jsonPath("$[0].reason").value("Created by mistake"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERIFIED", "ACCEPTED", "PUBLISHED", "REJECTED", "DELETED"})
    void deletionIsOnlyAllowedForCreatedRequests(String state) throws Exception {
        long id = createRequestIn(state);

        sendEvent(id, "DELETE", "Cleanup").andExpect(status().isConflict());
        assertThat(statusInDatabase(id)).isEqualTo(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "VERIFIED"})
    void bodyCanBeEditedInEditableStates(String state) throws Exception {
        long id = createRequestIn(state);

        mockMvc.perform(patch("/api/requests/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"body": "Updated loan application content", "name": "Ignored"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Updated loan application content"))
                .andExpect(jsonPath("$.name").value("Request in " + state))
                .andExpect(jsonPath("$.status").value(state));

        Map<String, Object> row = jdbcClient.sql("SELECT body, created_at, updated_at FROM requests WHERE id = ?")
                .param(id).query().singleRow();
        assertThat(row.get("BODY")).isEqualTo("Updated loan application content");
        assertThat(row.get("UPDATED_AT").toString()).isNotEqualTo(row.get("CREATED_AT").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "PUBLISHED", "REJECTED", "DELETED"})
    void bodyCannotBeEditedInLockedStates(String state) throws Exception {
        long id = createRequestIn(state);

        mockMvc.perform(patch("/api/requests/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"body": "Updated"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Request " + id + " cannot be modified in " + state + " state"));

        assertThat(jdbcClient.sql("SELECT body FROM requests WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("Body");
    }

    @Test
    void blankBodyEditIsRejected() throws Exception {
        long id = createRequest("Loan", "Body");

        mockMvc.perform(patch("/api/requests/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"body": "  "}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editingUnknownRequestReturns404() throws Exception {
        mockMvc.perform(patch("/api/requests/{id}", 999_999).contentType(MediaType.APPLICATION_JSON).content("""
                        {"body": "Updated"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void eventOnUnknownRequestReturns404() throws Exception {
        sendEvent(999_999, "VERIFY").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/requests/999999/audit")).andExpect(status().isNotFound());
    }

    @Test
    void browsingSupportsPaginationAndFiltering() throws Exception {
        for (int i = 1; i <= 12; i++) {
            createRequest("Loan " + i, "Body");
        }
        long mortgage = createRequest("MORTGAGE request", "Body");
        sendEvent(mortgage, "VERIFY").andExpect(status().isOk());

        mockMvc.perform(get("/api/requests"))
                .andExpect(jsonPath("$.content", hasSize(10)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(13))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(mortgage));
        mockMvc.perform(get("/api/requests?page=1&size=10"))
                .andExpect(jsonPath("$.content", hasSize(3)));
        mockMvc.perform(get("/api/requests?name=mortgage"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/requests?status=VERIFIED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(mortgage));
        mockMvc.perform(get("/api/requests?name=loan&status=VERIFIED"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/requests?size=101"))
                .andExpect(status().isBadRequest());
    }

    private Long publicationId(long id) {
        return jdbcClient.sql("SELECT publication_id FROM requests WHERE id = ?").param(id).query(Long.class).single();
    }
}
