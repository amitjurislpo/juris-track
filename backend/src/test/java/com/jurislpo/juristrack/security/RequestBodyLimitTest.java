package com.jurislpo.juristrack.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Request bodies are bounded before anything reads them (also unauthenticated ones), and so are the lists inside them. */
class RequestBodyLimitTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;

    @Test
    void aBodyAbove64KilobytesIsRefusedWith413() throws Exception {
        String hugeEmail = "a".repeat(70_000) + "@example.test";
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/auth/otp-requests")).content("{\"email\":\"" + hugeEmail + "\"}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        // a normal body still passes
        rateLimiter.clear();
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/auth/otp-requests")).content("{\"email\":\"" + TestData.uniqueEmail("small") + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void displayTimeZonesAreBoundedBeforeTheyAreResolved() throws Exception {
        rateLimiter.clear();
        TestData testData = new TestData(jdbcTemplate);
        String ownerEmail = TestData.uniqueEmail("bounded.owner");
        testData.createUser(testData.createBusiness("Bounded Co " + UUID.randomUUID()), "ADMIN", ownerEmail, null);
        Cookie ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        StringBuilder zones = new StringBuilder("[");
        for (int i = 0; i < 40; i++) zones.append(i == 0 ? "" : ",").append("\"Asia/Kolkata\"");
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/business")).cookie(ownerCookie).content("{\"displayTimeZones\":" + zones + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("displayTimeZones"));
        // duplicates up to the bound are still dropped as before
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/business")).cookie(ownerCookie)
                .content("{\"displayTimeZones\":[\"UTC\",\"UTC\",\"Asia/Kolkata\",\"UTC\",\"UTC\",\"UTC\",\"UTC\",\"UTC\",\"UTC\",\"UTC\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayTimeZones.length()").value(2));
    }
}
