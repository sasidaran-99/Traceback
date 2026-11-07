package com.traceback.api.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventIngestionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/events should ingest valid event and return 201 Created with Location header")
    void shouldIngestValidEvent() throws Exception {
        String payload = """
                {
                    "eventType": "COMMIT",
                    "source": "GITHUB",
                    "serviceName": "payment-service",
                    "environment": "production",
                    "timestamp": "2026-10-10T14:10:00Z",
                    "entityId": "c1a89b3",
                    "metadata": {
                        "author": "alice@example.com",
                        "message": "Refactor promotional discount math"
                    }
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.eventType").value("COMMIT"))
                .andExpect(jsonPath("$.source").value("GITHUB"))
                .andExpect(jsonPath("$.serviceName").value("payment-service"))
                .andExpect(jsonPath("$.environment").value("production"))
                .andExpect(jsonPath("$.entityId").value("c1a89b3"))
                .andExpect(jsonPath("$.metadata").value(containsString("alice@example.com")))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn();

        // Extract ID and verify GET /api/events/{id}
        String location = result.getResponse().getHeader("Location");
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("c1a89b3"));
    }

    @Test
    @DisplayName("POST /api/events should reject request missing required eventType with 400 Bad Request")
    void shouldRejectMissingEventType() throws Exception {
        String payload = """
                {
                    "source": "GITHUB",
                    "serviceName": "payment-service",
                    "environment": "production",
                    "timestamp": "2026-10-10T14:10:00Z",
                    "entityId": "c1a89b3"
                }
                """;

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("eventType"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("eventType is required"));
    }

    @Test
    @DisplayName("POST /api/events should reject blank serviceName with 400 Bad Request")
    void shouldRejectBlankServiceName() throws Exception {
        String payload = """
                {
                    "eventType": "DEPLOYMENT",
                    "source": "KUBERNETES",
                    "serviceName": "   ",
                    "environment": "production",
                    "timestamp": "2026-10-10T14:15:00Z",
                    "entityId": "dep-102"
                }
                """;

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'serviceName')]").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/events should reject invalid enum value with 400 Bad Request")
    void shouldRejectInvalidEnumValue() throws Exception {
        String payload = """
                {
                    "eventType": "UNKNOWN_EVENT_TYPE",
                    "source": "GITHUB",
                    "serviceName": "payment-service",
                    "environment": "production",
                    "timestamp": "2026-10-10T14:10:00Z",
                    "entityId": "c1a89b3"
                }
                """;

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("UNKNOWN_EVENT_TYPE")));
    }

    @Test
    @DisplayName("GET /api/events/{id} should return 404 Not Found for non-existing event")
    void shouldReturn404ForNonExistingEvent() throws Exception {
        mockMvc.perform(get("/api/events/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
