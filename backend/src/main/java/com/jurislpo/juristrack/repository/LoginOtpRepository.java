package com.jurislpo.juristrack.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code login_otps} table: one row per emailed sign-in code (global table; sign-in runs before any business is known). */
@Repository
public class LoginOtpRepository {

    /** The newest code of an email that can still be used. */
    public record OpenCode(UUID id, String codeHash, int attempts) {}

    private final JdbcTemplate jdbcTemplate;

    public LoginOtpRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Older unused codes stop working once a new one is sent. */
    public void invalidateOpenCodes(String email, Instant now) {
        jdbcTemplate.update("UPDATE login_otps SET used_at = ? WHERE email = ? AND used_at IS NULL", Timestamp.from(now), email);
    }

    public void insert(String email, String codeHash, Instant expiresAt, String ipAddress) {
        jdbcTemplate.update("INSERT INTO login_otps (email, code_hash, expires_at, ip_address) VALUES (?, ?, ?, ?)",
                email, codeHash, Timestamp.from(expiresAt), ipAddress);
    }

    /** The newest unused, unexpired code of the email, locked until the transaction ends. */
    public Optional<OpenCode> findOpenCodeForUpdate(String email, Instant now) {
        List<OpenCode> rows = jdbcTemplate.query("""
                SELECT id, code_hash, attempts FROM login_otps
                WHERE email = ? AND used_at IS NULL AND expires_at > ?
                ORDER BY created_at DESC LIMIT 1 FOR UPDATE""",
                (resultSet, rowNumber) -> new OpenCode(resultSet.getObject("id", UUID.class), resultSet.getString("code_hash"), resultSet.getInt("attempts")),
                email, Timestamp.from(now));
        return rows.stream().findFirst();
    }

    public void addAttempt(UUID id) {
        jdbcTemplate.update("UPDATE login_otps SET attempts = attempts + 1 WHERE id = ?", id);
    }

    public void markUsed(UUID id, Instant usedAt) {
        jdbcTemplate.update("UPDATE login_otps SET used_at = ? WHERE id = ?", Timestamp.from(usedAt), id);
    }
}
