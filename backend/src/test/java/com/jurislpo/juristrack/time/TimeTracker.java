package com.jurislpo.juristrack.time;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.SignIn;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Drives the time-tracking endpoints the way the web app does, for one signed-in user. */
final class TimeTracker {

    private final MockMvc mockMvc;
    private final Cookie accessCookie;

    TimeTracker(MockMvc mockMvc, Cookie accessCookie) {
        this.mockMvc = mockMvc;
        this.accessCookie = accessCookie;
    }

    /** {@code POST /api/v1/me/time-events} with {@code type}; the caller checks the answer. */
    ResultActions send(String type) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).cookie(accessCookie).content("{\"type\":\"" + type + "\"}"));
    }

    /** Sends an event that must change the state (201). */
    String apply(String type) throws Exception {
        return send(type).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    /** {@code GET /api/v1/me/time} (200). */
    String state() throws Exception {
        return mockMvc.perform(get("/api/v1/me/time").cookie(accessCookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** A value of the current state, e.g. {@code $.data.workday.productiveSeconds}. */
    <T> T stateValue(String jsonPath) throws Exception {
        return JsonPath.read(state(), jsonPath);
    }

    ResultActions history(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/workdays/mine" + (query.isEmpty() ? "" : "?" + query)).cookie(accessCookie));
    }

    ResultActions workday(Object workdayId) throws Exception {
        return mockMvc.perform(get("/api/v1/workdays/" + workdayId).cookie(accessCookie));
    }
}
