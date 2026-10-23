package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.WorkdayStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The workdays behind the reports: one set-based query per request for a date range, with the employee and the manager of the day
 * (the workday's {@code manager_id} snapshot) joined in, restricted to the people the caller may see. The intervals of the open
 * workdays are loaded separately in one query (WorkSessionRepository / BreakSessionRepository).
 */
@Repository
public class ReportQueryRepository {

    /**
     * One workday as stored, with the names the report shows.
     *
     * @param managerId          the employee's manager when the day started (snapshot), or null
     * @param managerName        that manager's "first last" today, or null
     * @param productiveSeconds  cached on completion (null while open)
     * @param breakSeconds       cached on completion (null while open)
     * @param breakCount         cached on completion (null while open)
     */
    public record StoredWorkday(UUID id, LocalDate date, UUID employeeId, String employeeCode, String employeeFirstName,
            String employeeLastName, UUID managerId, String managerName, WorkdayStatus status, Instant startedAt, Instant endedAt,
            Integer productiveSeconds, Integer breakSeconds, Integer breakCount) {}

    /**
     * What to read.
     *
     * @param businessId    the business (row-level security applies as well)
     * @param from          first workday date (included)
     * @param to            last workday date (included)
     * @param employeeIds   only these employees; null = every user of the business
     * @param employeeId    only this employee, or null
     * @param managerId     only workdays whose manager snapshot is this user, or null
     * @param isOpen        true = open days only, false = ended days only, null = both
     * @param searchPattern ILIKE pattern on the employee's names, email and code, or null
     */
    public record WorkdayFilter(UUID businessId, LocalDate from, LocalDate to, Set<UUID> employeeIds, UUID employeeId, UUID managerId,
            Boolean isOpen, String searchPattern) {}

    private static final String WORKDAY_SELECT = """
            SELECT workday.id, workday.work_date, workday.employee_id, employee.employee_code, employee.first_name, employee.last_name,
                   workday.manager_id, NULLIF(TRIM(manager.first_name || ' ' || manager.last_name), '') AS manager_name,
                   workday.status::text AS status, workday.started_at, workday.ended_at, workday.productive_seconds, workday.break_seconds,
                   workday.break_count
            FROM workdays workday
            JOIN users employee ON employee.id = workday.employee_id
            LEFT JOIN users manager ON manager.id = workday.manager_id
            """;

    private static final RowMapper<StoredWorkday> WORKDAY_MAPPER = (resultSet, rowNumber) -> new StoredWorkday(
            resultSet.getObject("id", UUID.class), resultSet.getObject("work_date", LocalDate.class),
            resultSet.getObject("employee_id", UUID.class), resultSet.getString("employee_code"), resultSet.getString("first_name"),
            resultSet.getString("last_name"), resultSet.getObject("manager_id", UUID.class), resultSet.getString("manager_name"),
            WorkdayStatus.valueOf(resultSet.getString("status")), instantOf(resultSet, "started_at"), instantOf(resultSet, "ended_at"),
            integerOf(resultSet, "productive_seconds"), integerOf(resultSet, "break_seconds"), integerOf(resultSet, "break_count"));

    private final JdbcTemplate jdbcTemplate;

    public ReportQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Every workday matching the filter, newest date first (the service sorts again by the requested key). */
    public List<StoredWorkday> findWorkdays(WorkdayFilter filter) {
        if (filter.employeeIds() != null && filter.employeeIds().isEmpty()) return List.of();
        List<Object> arguments = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE workday.business_id = ? AND workday.work_date BETWEEN ? AND ?");
        arguments.add(filter.businessId());
        arguments.add(filter.from());
        arguments.add(filter.to());
        if (filter.employeeIds() != null) {
            // one array parameter, however many people are in scope; the UUIDs' own text is the only content
            where.append(" AND workday.employee_id = ANY (CAST(? AS uuid[]))");
            arguments.add(filter.employeeIds().stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}")));
        }
        if (filter.employeeId() != null) {
            where.append(" AND workday.employee_id = ?");
            arguments.add(filter.employeeId());
        }
        if (filter.managerId() != null) {
            where.append(" AND workday.manager_id = ?");
            arguments.add(filter.managerId());
        }
        if (filter.isOpen() != null) where.append(filter.isOpen() ? " AND workday.status <> 'COMPLETED'" : " AND workday.status = 'COMPLETED'");
        if (filter.searchPattern() != null) {
            where.append(" AND (employee.first_name ILIKE ? OR employee.last_name ILIKE ? OR (employee.first_name || ' ' || employee.last_name) ILIKE ?"
                    + " OR employee.email ILIKE ? OR COALESCE(employee.employee_code, '') ILIKE ?)");
            for (int i = 0; i < 5; i++) arguments.add(filter.searchPattern());
        }
        return jdbcTemplate.query(WORKDAY_SELECT + where + " ORDER BY workday.work_date DESC, workday.started_at, workday.id", WORKDAY_MAPPER,
                arguments.toArray());
    }

    private static Instant instantOf(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Integer integerOf(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}
