package com.jurislpo.juristrack;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Rows for API tests, written in system mode (every business). Every email is unique, so tests never collide. */
public final class TestData {

    private final JdbcTemplate jdbcTemplate;

    public TestData(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID createBusiness(String name) {
        UUID businessId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO businesses (id, name) VALUES (?, ?)", businessId, name);
        return businessId;
    }

    /** A user of {@code businessId} (null and role SUPER_ADMIN for the platform user); returns the id. */
    public UUID createUser(UUID businessId, String role, String email, UUID managerId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, business_id, first_name, last_name, email, role, manager_id) VALUES (?, ?, 'Test', 'Person', ?, ?::user_role, ?)",
                userId, businessId, email, role, managerId);
        return userId;
    }

    /** A unique lower-case email such as {@code employee.1a2b3c4d@test.juristrack.test}. */
    public static String uniqueEmail(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().substring(0, 8) + "@test.juristrack.test";
    }

    public void deactivate(UUID userId) {
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", userId);
    }
}
