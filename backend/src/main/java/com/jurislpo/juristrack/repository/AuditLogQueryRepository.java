package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.common.ListQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read queries over the audit log for the audit pages and the CSV export. Row-level security decides which rows exist for the caller
 * (an owner's request sees their business only, the super admin's system-mode request sees every row); the filters narrow it down.
 * Names of the actor, the subject and the business are joined at read time, so a renamed person shows their current name; a person
 * outside the caller's business (the platform super admin in an owner's log) has no name here.
 */
@Repository
public class AuditLogQueryRepository {

    /**
     * One stored entry with the names resolved.
     *
     * @param beforeJson / afterJson the stored JSON as text (null when empty)
     */
    public record AuditLogRecord(UUID id, Instant createdAt, UUID businessId, String businessName, UUID actorId, String actorName, String actorRole,
            UUID subjectId, String subjectName, String action, String category, String outcome, String entityType, String entityId, String source,
            String ipAddress, String requestId, String reason, String beforeJson, String afterJson, String userAgent, String httpMethod,
            String requestPath) {}

    /**
     * The filters of the audit log (every one optional).
     *
     * @param businessId       only this business (super admin)
     * @param isWithoutBusiness only entries outside any business (super admin: platform events)
     * @param from             created at or after
     * @param toExclusive      created before
     * @param action           the action, exactly, or a prefix when {@code isActionPrefix}
     */
    public record AuditLogFilter(UUID businessId, boolean isWithoutBusiness, Instant from, Instant toExclusive, UUID actorId, UUID subjectId,
            String action, boolean isActionPrefix, String category, String entityType, String entityId, String outcome, String source) {}

    /** The SQL behind each sort key ({@code %s} is the direction); ties are broken by time and id, newest first. */
    public static final Map<String, String> SORT_COLUMNS = Map.of(
            "createdAt", "entry.created_at %s",
            "action", "entry.action %s",
            "category", "entry.category %s",
            "outcome", "entry.outcome %s",
            "actor", "lower(NULLIF(TRIM(actor.first_name || ' ' || actor.last_name), '')) %s NULLS LAST",
            "subject", "lower(NULLIF(TRIM(subject.first_name || ' ' || subject.last_name), '')) %s NULLS LAST");

    private static final String FROM = """
            FROM audit_logs entry
            LEFT JOIN users actor ON actor.id = entry.actor_id
            LEFT JOIN users subject ON subject.id = entry.subject_id
            LEFT JOIN businesses business ON business.id = entry.business_id
            """;

    private static final String SELECT = """
            SELECT entry.id, entry.created_at, entry.business_id, business.name AS business_name,
                   entry.actor_id, NULLIF(TRIM(actor.first_name || ' ' || actor.last_name), '') AS actor_name, entry.actor_role,
                   entry.subject_id, NULLIF(TRIM(subject.first_name || ' ' || subject.last_name), '') AS subject_name,
                   entry.action, entry.category, entry.outcome, entry.entity_type, entry.entity_id, entry.source, entry.ip_address, entry.request_id,
                   entry.reason, entry."before"::text AS before_json, entry."after"::text AS after_json, entry.user_agent, entry.http_method,
                   entry.request_path
            """ + FROM;

    private static final RowMapper<AuditLogRecord> RECORD_MAPPER = (resultSet, rowNumber) -> new AuditLogRecord(resultSet.getObject("id", UUID.class),
            instantOf(resultSet, "created_at"), resultSet.getObject("business_id", UUID.class), resultSet.getString("business_name"),
            resultSet.getObject("actor_id", UUID.class), resultSet.getString("actor_name"), resultSet.getString("actor_role"),
            resultSet.getObject("subject_id", UUID.class), resultSet.getString("subject_name"), resultSet.getString("action"),
            resultSet.getString("category"), resultSet.getString("outcome"), resultSet.getString("entity_type"), resultSet.getString("entity_id"),
            resultSet.getString("source"), resultSet.getString("ip_address"), resultSet.getString("request_id"), resultSet.getString("reason"),
            resultSet.getString("before_json"), resultSet.getString("after_json"), resultSet.getString("user_agent"), resultSet.getString("http_method"),
            resultSet.getString("request_path"));

    private final JdbcTemplate jdbcTemplate;

    public AuditLogQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One page of the entries that match, sorted; {@code limit} rows from {@code offset}. */
    public List<AuditLogRecord> findPage(AuditLogFilter filter, ListQuery listQuery, long offset, int limit) {
        List<Object> arguments = new ArrayList<>();
        String where = whereClause(filter, listQuery, arguments);
        String direction = listQuery.isAscending() ? "ASC" : "DESC";
        String order = String.format(SORT_COLUMNS.get(listQuery.sort()), direction) + ", entry.created_at DESC, entry.id DESC";
        arguments.add(limit);
        arguments.add(offset);
        return jdbcTemplate.query(SELECT + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", RECORD_MAPPER, arguments.toArray());
    }

    /** How many entries match. */
    public long count(AuditLogFilter filter, ListQuery listQuery) {
        List<Object> arguments = new ArrayList<>();
        String where = whereClause(filter, listQuery, arguments);
        Long total = jdbcTemplate.queryForObject("SELECT count(*) " + FROM + where, Long.class, arguments.toArray());
        return total == null ? 0 : total;
    }

    /** One entry, when the caller may see it. */
    public Optional<AuditLogRecord> findById(UUID auditLogId) {
        return jdbcTemplate.query(SELECT + "WHERE entry.id = ?", RECORD_MAPPER, auditLogId).stream().findFirst();
    }

    private static String whereClause(AuditLogFilter filter, ListQuery listQuery, List<Object> arguments) {
        StringBuilder where = new StringBuilder("WHERE true");
        if (filter.businessId() != null) {
            where.append(" AND entry.business_id = ?");
            arguments.add(filter.businessId());
        }
        if (filter.isWithoutBusiness()) where.append(" AND entry.business_id IS NULL");
        if (filter.from() != null) {
            where.append(" AND entry.created_at >= ?");
            arguments.add(Timestamp.from(filter.from()));
        }
        if (filter.toExclusive() != null) {
            where.append(" AND entry.created_at < ?");
            arguments.add(Timestamp.from(filter.toExclusive()));
        }
        if (filter.actorId() != null) {
            where.append(" AND entry.actor_id = ?");
            arguments.add(filter.actorId());
        }
        if (filter.subjectId() != null) {
            where.append(" AND entry.subject_id = ?");
            arguments.add(filter.subjectId());
        }
        if (filter.action() != null) {
            if (filter.isActionPrefix()) {
                where.append(" AND entry.action LIKE ?");
                arguments.add(filter.action().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            } else {
                where.append(" AND entry.action = ?");
                arguments.add(filter.action());
            }
        }
        appendEquals(where, arguments, "entry.category", filter.category());
        appendEquals(where, arguments, "entry.entity_type", filter.entityType());
        appendEquals(where, arguments, "entry.entity_id", filter.entityId());
        appendEquals(where, arguments, "entry.outcome", filter.outcome());
        appendEquals(where, arguments, "entry.source", filter.source());
        String searchPattern = listQuery.searchPattern();
        if (searchPattern != null) {
            where.append(" AND (entry.action ILIKE ? OR entry.request_id ILIKE ? OR COALESCE(entry.entity_id, '') ILIKE ? OR COALESCE(entry.ip_address, '') ILIKE ?"
                    + " OR (actor.first_name || ' ' || actor.last_name) ILIKE ? OR (subject.first_name || ' ' || subject.last_name) ILIKE ?)");
            for (int i = 0; i < 6; i++) arguments.add(searchPattern);
        }
        return where.toString();
    }

    private static void appendEquals(StringBuilder where, List<Object> arguments, String column, String value) {
        if (value == null) return;
        where.append(" AND ").append(column).append(" = ?");
        arguments.add(value);
    }

    private static Instant instantOf(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
