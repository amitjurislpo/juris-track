package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Signed-in devices ({@code sessions}) and their rotating refresh tokens ({@code refresh_tokens}). Both are global tables
 * (no business, no row-level security): they are read before any business is known.
 */
@Repository
public class SessionRepository {

    /** A session with its user and the user's business, as the request filter needs it. */
    public record SessionUser(String sessionId, UUID sessionUserId, Instant expiresAt, Instant revokedAt, Instant lastSeenAt, UUID userId,
            UUID businessId, String businessName, Role role, String firstName, String lastName, String email, boolean isActive,
            boolean isLoginEnabled, boolean isBusinessSuspended) {}

    /** A session that has not ended, and its user. */
    public record OpenSession(String sessionId, UUID userId) {}

    /** A refresh token with its session and the session's user. */
    public record RefreshTokenSession(String sessionId, UUID userId, Instant usedAt, Instant sessionCreatedAt, Instant sessionExpiresAt,
            Instant revokedAt, boolean isUserActive, boolean isLoginEnabled) {}

    private final JdbcTemplate jdbcTemplate;

    public SessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertSession(String sessionId, UUID userId, Instant expiresAt, String ipAddress, String userAgent) {
        jdbcTemplate.update("INSERT INTO sessions (id, user_id, expires_at, ip_address, user_agent) VALUES (?, ?, ?, ?, ?)",
                sessionId, userId, Timestamp.from(expiresAt), ipAddress, userAgent);
    }

    public void insertRefreshToken(String tokenHash, String sessionId, Instant expiresAt, String ipAddress, String userAgent) {
        jdbcTemplate.update("INSERT INTO refresh_tokens (token_hash, session_id, expires_at, ip_address, user_agent) VALUES (?, ?, ?, ?, ?)",
                tokenHash, sessionId, Timestamp.from(expiresAt), ipAddress, userAgent);
    }

    /** The session with its user (zero or one); system mode. */
    public Optional<SessionUser> findSessionUser(String sessionId) {
        List<SessionUser> rows = jdbcTemplate.query("""
                SELECT device_session.id AS session_id, device_session.user_id AS session_user_id, device_session.expires_at, device_session.revoked_at, device_session.last_seen_at,
                       person.id AS user_id, person.business_id, business.name AS business_name, person.role::text AS role,
                       person.first_name, person.last_name, person.email, person.is_active, person.is_login_enabled,
                       COALESCE(business.is_suspended, false) AS is_business_suspended
                FROM sessions device_session
                JOIN users person ON person.id = device_session.user_id
                LEFT JOIN businesses business ON business.id = person.business_id
                WHERE device_session.id = ?""",
                (resultSet, rowNumber) -> new SessionUser(resultSet.getString("session_id"), resultSet.getObject("session_user_id", UUID.class),
                        instantOf(resultSet, "expires_at"), instantOf(resultSet, "revoked_at"), instantOf(resultSet, "last_seen_at"),
                        resultSet.getObject("user_id", UUID.class), resultSet.getObject("business_id", UUID.class), resultSet.getString("business_name"),
                        Role.valueOf(resultSet.getString("role")), resultSet.getString("first_name"), resultSet.getString("last_name"),
                        resultSet.getString("email"), resultSet.getBoolean("is_active"),
                        resultSet.getBoolean("is_login_enabled"), resultSet.getBoolean("is_business_suspended")),
                sessionId);
        return rows.stream().findFirst();
    }

    /** The refresh token with this hash, its row locked until the transaction ends ({@code FOR UPDATE}); empty when unknown. */
    public Optional<RefreshTokenSession> findRefreshTokenForUpdate(String tokenHash) {
        List<RefreshTokenSession> rows = jdbcTemplate.query("""
                SELECT token.session_id, token.used_at, device_session.user_id, device_session.created_at AS session_created_at,
                       device_session.expires_at AS session_expires_at, device_session.revoked_at, person.is_active, person.is_login_enabled
                FROM refresh_tokens token
                JOIN sessions device_session ON device_session.id = token.session_id
                JOIN users person ON person.id = device_session.user_id
                WHERE token.token_hash = ? FOR UPDATE OF token""",
                (resultSet, rowNumber) -> new RefreshTokenSession(resultSet.getString("session_id"), resultSet.getObject("user_id", UUID.class),
                        instantOf(resultSet, "used_at"), instantOf(resultSet, "session_created_at"), instantOf(resultSet, "session_expires_at"),
                        instantOf(resultSet, "revoked_at"), resultSet.getBoolean("is_active"),
                        resultSet.getBoolean("is_login_enabled")),
                tokenHash);
        return rows.stream().findFirst();
    }

    public void markRefreshTokenUsed(String tokenHash, Instant usedAt) {
        jdbcTemplate.update("UPDATE refresh_tokens SET used_at = ? WHERE token_hash = ?", Timestamp.from(usedAt), tokenHash);
    }

    public void updateLastSeen(String sessionId, Instant lastSeenAt) {
        jdbcTemplate.update("UPDATE sessions SET last_seen_at = ? WHERE id = ?", Timestamp.from(lastSeenAt), sessionId);
    }

    /** Ends one session unless it already ended. */
    public void revoke(String sessionId, String reason, Instant revokedAt) {
        jdbcTemplate.update("UPDATE sessions SET revoked_at = ?, revoked_reason = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(revokedAt), reason, sessionId);
    }

    /** Ends every open session of a user (deactivation, login switched off); returns how many were ended. */
    public int revokeAllOfUser(UUID userId, String reason, Instant revokedAt) {
        return jdbcTemplate.update("UPDATE sessions SET revoked_at = ?, revoked_reason = ? WHERE user_id = ? AND revoked_at IS NULL",
                Timestamp.from(revokedAt), reason, userId);
    }

    /** The session (zero or one) a refresh token belongs to, while it is not ended, with its user. */
    public Optional<OpenSession> findOpenSessionByRefreshToken(String tokenHash) {
        return jdbcTemplate.query("""
                SELECT device_session.id, device_session.user_id FROM refresh_tokens token
                JOIN sessions device_session ON device_session.id = token.session_id
                WHERE token.token_hash = ? AND device_session.revoked_at IS NULL""",
                (resultSet, rowNumber) -> new OpenSession(resultSet.getString("id"), resultSet.getObject("user_id", UUID.class)), tokenHash)
                .stream().findFirst();
    }

    private static Instant instantOf(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
