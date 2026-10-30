package com.jurislpo.juristrack;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Signs in the way the web app does: ask for the emailed code, then post it (tests use the fixed code 000000). */
public final class SignIn {

    public static final String CODE = "000000";
    /** The web app's origin in the tests (juristrack.app-url of IntegrationTest). */
    public static final String ORIGIN = "http://localhost:3006";

    private SignIn() {}

    /** The two session cookies of a signed-in browser. */
    public record SessionCookies(Cookie accessCookie, Cookie refreshCookie) {}

    /** A write from the web app: JSON with the app's Origin header. */
    public static MockHttpServletRequestBuilder fromWebApp(MockHttpServletRequestBuilder request) {
        return request.header("origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    public static ResultActions requestCode(MockMvc mockMvc, String email) throws Exception {
        return mockMvc.perform(fromWebApp(post("/api/v1/auth/otp-requests")).content("{\"email\":\"" + email + "\"}"));
    }

    public static ResultActions postCode(MockMvc mockMvc, String email, String code, boolean isRemembered) throws Exception {
        return mockMvc.perform(fromWebApp(post("/api/v1/auth/sessions"))
                .content("{\"email\":\"" + email + "\",\"otp\":\"" + code + "\",\"remember\":" + isRemembered + "}"));
    }

    /** Requests a code and signs in with it ("keep me signed in" on); fails the test unless both succeed. */
    public static SessionCookies session(MockMvc mockMvc, String email) throws Exception {
        requestCode(mockMvc, email).andExpect(status().isOk());
        return cookiesOf(postCode(mockMvc, email, CODE, true).andExpect(status().isCreated()).andReturn().getResponse());
    }

    public static SessionCookies cookiesOf(MockHttpServletResponse response) {
        return new SessionCookies(new Cookie("jt_at", cookieValue(response, "jt_at")), new Cookie("jt_rt", cookieValue(response, "jt_rt")));
    }

    /** The value a Set-Cookie header gives the cookie {@code name}, or null. */
    public static String cookieValue(MockHttpServletResponse response, String name) {
        return setCookieHeader(response, name) == null ? null : valueOf(setCookieHeader(response, name), name);
    }

    /** The whole Set-Cookie header for {@code name}, or null. */
    public static String setCookieHeader(MockHttpServletResponse response, String name) {
        for (String header : response.getHeaders("Set-Cookie")) if (header.startsWith(name + "=")) return header;
        return null;
    }

    private static String valueOf(String header, String name) {
        Matcher matcher = Pattern.compile("^" + name + "=([^;]*)").matcher(header);
        return matcher.find() ? matcher.group(1) : null;
    }
}
