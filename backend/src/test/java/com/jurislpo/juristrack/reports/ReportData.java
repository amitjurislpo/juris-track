package com.jurislpo.juristrack.reports;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Workdays for the report tests, written straight into the database in system mode (like {@code TestData}): an ended day with its
 * stored totals, or an open day with its running work session. The manager snapshot is set explicitly, so a test can show the
 * manager "at the time" being someone else than today's manager.
 */
final class ReportData {

    private final JdbcTemplate jdbcTemplate;

    ReportData(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** An ended workday with one work session over the whole day and the given stored totals; returns its id. */
    UUID endedWorkday(UUID businessId, UUID employeeId, UUID managerId, String date, String startedAt, String endedAt, int productiveSeconds,
            int breakSeconds, int breakCount) {
        UUID workdayId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO workdays (id, business_id, employee_id, manager_id, work_date, time_zone, started_at, ended_at, status,
                                      active_employee_id, productive_seconds, break_seconds, break_count)
                VALUES (?, ?, ?, ?, ?, 'Asia/Kolkata', ?, ?, 'COMPLETED', NULL, ?, ?, ?)""",
                workdayId, businessId, employeeId, managerId, LocalDate.parse(date), timestamp(startedAt), timestamp(endedAt), productiveSeconds,
                breakSeconds, breakCount);
        jdbcTemplate.update("INSERT INTO work_sessions (business_id, employee_id, workday_id, started_at, ended_at) VALUES (?, ?, ?, ?, ?)",
                businessId, employeeId, workdayId, timestamp(startedAt), timestamp(endedAt));
        return workdayId;
    }

    /** An open (WORKING) workday with a running work session since it started; returns its id. */
    UUID openWorkday(UUID businessId, UUID employeeId, UUID managerId, String date, String startedAt) {
        UUID workdayId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO workdays (id, business_id, employee_id, manager_id, work_date, time_zone, started_at, status, active_employee_id)
                VALUES (?, ?, ?, ?, ?, 'Asia/Kolkata', ?, 'WORKING', ?)""",
                workdayId, businessId, employeeId, managerId, LocalDate.parse(date), timestamp(startedAt), employeeId);
        jdbcTemplate.update("INSERT INTO work_sessions (business_id, employee_id, workday_id, started_at, open_workday_id) VALUES (?, ?, ?, ?, ?)",
                businessId, employeeId, workdayId, timestamp(startedAt), workdayId);
        return workdayId;
    }

    /** The report audit entries written for {@code actorId}, oldest first: {@code action|subject|entityId|after}. */
    List<String> reportAuditEntries(UUID actorId) {
        return jdbcTemplate.query("SELECT action, subject_id, entity_type, entity_id, \"after\"::text AS details FROM audit_logs "
                + "WHERE actor_id = ? AND action LIKE 'reports.%' ORDER BY created_at, id",
                (resultSet, rowNumber) -> resultSet.getString("action") + "|" + resultSet.getString("subject_id") + "|"
                        + resultSet.getString("entity_type") + "|" + resultSet.getString("entity_id") + "|" + resultSet.getString("details"),
                actorId);
    }

    private static Timestamp timestamp(String isoInstant) {
        return Timestamp.from(Instant.parse(isoInstant));
    }
}
