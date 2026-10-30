package com.jurislpo.juristrack.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The health endpoint is open and UP (database included); everything under /api/v1 needs a session and says so as problem+json. */
class HealthTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void healthIsUpWithoutSigningIn() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void apiCallsWithoutASessionAnswer401ProblemDetails() throws Exception {
        mockMvc.perform(get("/api/v1/workdays/mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."))
                .andExpect(jsonPath("$.instance").value("/api/v1/workdays/mine"));
    }

    @Test
    void otherActuatorEndpointsAreClosed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
