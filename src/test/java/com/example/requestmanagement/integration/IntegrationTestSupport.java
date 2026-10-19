package com.example.requestmanagement.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("DELETE FROM request_audit").update();
        jdbcClient.sql("DELETE FROM requests").update();
    }

    protected long createRequest(String name, String body) throws Exception {
        String response = mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\", \"body\": \"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    protected ResultActions sendEvent(long id, String event) throws Exception {
        return sendEvent(id, event, null);
    }

    protected ResultActions sendEvent(long id, String event, String reason) throws Exception {
        String json = reason == null
                ? "{\"event\": \"" + event + "\"}"
                : "{\"event\": \"" + event + "\", \"reason\": \"" + reason + "\"}";
        return mockMvc.perform(post("/api/requests/{id}/events", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    protected long createRequestIn(String status) throws Exception {
        long id = createRequest("Request in " + status, "Body");
        switch (status) {
            case "CREATED" -> {
            }
            case "VERIFIED" -> verify(id);
            case "ACCEPTED" -> accept(id);
            case "PUBLISHED" -> {
                accept(id);
                sendEvent(id, "PUBLISH").andExpect(status().isOk());
            }
            case "REJECTED" -> {
                verify(id);
                sendEvent(id, "REJECT", "Not eligible").andExpect(status().isOk());
            }
            case "DELETED" -> sendEvent(id, "DELETE", "Created by mistake").andExpect(status().isOk());
            default -> throw new IllegalArgumentException(status);
        }
        return id;
    }

    protected String statusInDatabase(long id) {
        return jdbcClient.sql("SELECT status FROM requests WHERE id = ?").param(id).query(String.class).single();
    }

    protected int auditCount(long id) {
        return jdbcClient.sql("SELECT COUNT(*) FROM request_audit WHERE request_id = ?")
                .param(id).query(Integer.class).single();
    }

    private void verify(long id) throws Exception {
        sendEvent(id, "VERIFY").andExpect(status().isOk());
    }

    private void accept(long id) throws Exception {
        verify(id);
        sendEvent(id, "ACCEPT").andExpect(status().isOk());
    }
}
