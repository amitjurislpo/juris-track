package com.jurislpo.juristrack.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.common.ListQuery;
import com.jurislpo.juristrack.engine.ShiftSummary;
import com.jurislpo.juristrack.entity.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read queries over the users of one business (the owner's employee list and detail, the reporting line, uniqueness checks) and
 * the signed-in user's profile. Every query names the business explicitly as well; row-level security already hides the others.
 */
@Repository
public class EmployeeQueryRepository {

    /**
     * One employee as the owner's list and detail show them.
     *
     * @param managerName       the manager's "first last", or null without a manager
     * @param activeReportCount how many active users report directly to this person
     */
    public record EmployeeRow(UUID id, String employeeCode, String firstName, String lastName, String email, Role role,
            @JsonProperty("isActive") boolean isActive, @JsonProperty("isLoginEnabled") boolean isLoginEnabled, UUID managerId,
            String managerName, Instant lastLoginAt, Instant createdAt, long activeReportCount, ShiftSummary shift) {

        /** The same row with the person's current shift. */
        public EmployeeRow withShift(ShiftSummary currentShift) {
            return new EmployeeRow(id, employeeCode, firstName, lastName, email, role, isActive, isLoginEnabled, managerId, managerName,
                    lastLoginAt, createdAt, activeReportCount, currentShift);
        }
    }

    /**
     * Filters of the owner's employee list.
     *
     * @param isActive  true = active only, false = deactivated only, null = everybody
     * @param roles     the roles to include (empty = every business role)
     * @param managerId only the direct reports of this user, or null
     */
    public record EmployeeFilter(Boolean isActive, List<Role> roles, UUID managerId) {}

    /** What {@code GET /api/me} adds to the signed-in user. */
    public record UserProfileDetails(String employeeCode, String managerName, String businessTimeZone) {}

    /** The SQL behind each sort key of {@code GET /api/employees}, ascending ({@code %s} is the direction). */
    public static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "lower(person.first_name) %1$s, lower(person.last_name) %1$s",
            "employeeCode", "person.employee_code %s NULLS LAST",
            "email", "person.email %s",
            "role", "person.role %s",
            "status", "(NOT person.is_active) %s",
            "lastLoginAt", "person.last_login_at %s NULLS LAST");

    private static final String ROW_SELECT = """
            SELECT person.id, person.employee_code, person.first_name, person.last_name, person.email, person.role::text AS role, person.is_active, person.is_login_enabled,
                   person.manager_id, NULLIF(TRIM(manager.first_name || ' ' || manager.last_name), '') AS manager_name, person.last_login_at,
                   person.created_at,
                   (SELECT count(*) FROM users report WHERE report.manager_id = person.id AND report.is_active) AS active_report_count
            FROM users person
            LEFT JOIN users manager ON manager.id = person.manager_id
            """;

    private static final RowMapper<EmployeeRow> ROW_MAPPER = (resultSet, rowNumber) -> new EmployeeRow(resultSet.getObject("id", UUID.class),
            resultSet.getString("employee_code"), resultSet.getString("first_name"), resultSet.getString("last_name"), resultSet.getString("email"),
            Role.valueOf(resultSet.getString("role")), resultSet.getBoolean("is_active"),
            resultSet.getBoolean("is_login_enabled"), resultSet.getObject("manager_id", UUID.class),
            resultSet.getString("manager_name"), instantOf(resultSet, "last_login_at"), instantOf(resultSet, "created_at"),
            resultSet.getLong("active_report_count"), null);

    private final JdbcTemplate jdbcTemplate;

    public EmployeeQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One page of the business's users (never the platform super admin), filtered, searched and sorted. */
    public List<EmployeeRow> findPage(UUID businessId, EmployeeFilter filter, ListQuery listQuery) {
        List<Object> arguments = new ArrayList<>();
        String where = whereClause(businessId, filter, listQuery, arguments);
        String direction = listQuery.isAscending() ? "ASC" : "DESC";
        String order = String.format(SORT_COLUMNS.get(listQuery.sort()), direction)
                + ", lower(person.first_name), lower(person.last_name), person.id";
        arguments.add(listQuery.pageSize());
        arguments.add(listQuery.offset());
        return jdbcTemplate.query(ROW_SELECT + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", ROW_MAPPER, arguments.toArray());
    }

    /** How many users match the same filters. */
    public long count(UUID businessId, EmployeeFilter filter, ListQuery listQuery) {
        List<Object> arguments = new ArrayList<>();
        String where = whereClause(businessId, filter, listQuery, arguments);
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM users person " + where, Long.class, arguments.toArray());
        return total == null ? 0 : total;
    }

    private static String whereClause(UUID businessId, EmployeeFilter filter, ListQuery listQuery, List<Object> arguments) {
        StringBuilder where = new StringBuilder("WHERE person.business_id = ? AND person.role <> 'SUPER_ADMIN'");
        arguments.add(businessId);
        if (filter.isActive() != null) {
            where.append(" AND person.is_active = ?");
            arguments.add(filter.isActive());
        }
        if (!filter.roles().isEmpty()) {
            where.append(" AND person.role::text IN (").append(String.join(", ", filter.roles().stream().map(role -> "?").toList())).append(")");
            filter.roles().forEach(role -> arguments.add(role.name()));
        }
        if (filter.managerId() != null) {
            where.append(" AND person.manager_id = ?");
            arguments.add(filter.managerId());
        }
        String searchPattern = listQuery.searchPattern();
        if (searchPattern != null) {
            where.append(" AND (person.first_name ILIKE ? OR person.last_name ILIKE ? OR (person.first_name || ' ' || person.last_name) ILIKE ?"
                    + " OR person.email ILIKE ? OR COALESCE(person.employee_code, '') ILIKE ?)");
            for (int i = 0; i < 5; i++) arguments.add(searchPattern);
        }
        return where.toString();
    }

    /** One user of the business (never the super admin); empty when unknown or in another business. */
    public Optional<EmployeeRow> findRow(UUID businessId, UUID userId) {
        return jdbcTemplate.query(ROW_SELECT + "WHERE person.business_id = ? AND person.id = ? AND person.role <> 'SUPER_ADMIN'", ROW_MAPPER,
                businessId, userId).stream().findFirst();
    }

    /** True when any user of any business (or the super admin), other than {@code excludedUserId}, has this email (V2 function). */
    public boolean isEmailInUse(String email, UUID excludedUserId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT juristrack_is_email_in_use(?, ?)", Boolean.class, email, excludedUserId));
    }

    /** True when another user of the business has this employee code. */
    public boolean isEmployeeCodeInUse(UUID businessId, String employeeCode, UUID excludedUserId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE business_id = ? AND employee_code = ? AND (CAST(? AS uuid) IS NULL OR id <> ?))",
                Boolean.class, businessId, employeeCode, excludedUserId, excludedUserId));
    }

    /** Every user's manager in the business (the whole reporting line, active and deactivated users alike). */
    public Map<UUID, UUID> findManagerIdsByEmployeeId(UUID businessId) {
        Map<UUID, UUID> managerIdByEmployeeId = new HashMap<>();
        jdbcTemplate.query("SELECT id, manager_id FROM users WHERE business_id = ?",
                resultSet -> {
                    managerIdByEmployeeId.put(resultSet.getObject("id", UUID.class), resultSet.getObject("manager_id", UUID.class));
                }, businessId);
        return managerIdByEmployeeId;
    }

    /** How many active owners (ADMIN) the business has besides {@code excludedUserId}. */
    public long countOtherActiveOwners(UUID businessId, UUID excludedUserId) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE business_id = ? AND role = 'ADMIN' AND is_active AND id <> ?",
                Long.class, businessId, excludedUserId);
        return count == null ? 0 : count;
    }

    /** Employee code, manager name and business time zone of a user (all null for the super admin). */
    public UserProfileDetails findProfileDetails(UUID userId) {
        return jdbcTemplate.query("""
                SELECT person.employee_code, NULLIF(TRIM(manager.first_name || ' ' || manager.last_name), '') AS manager_name,
                       business.time_zone AS business_time_zone
                FROM users person
                LEFT JOIN users manager ON manager.id = person.manager_id
                LEFT JOIN businesses business ON business.id = person.business_id
                WHERE person.id = ?""",
                (resultSet, rowNumber) -> new UserProfileDetails(resultSet.getString("employee_code"), resultSet.getString("manager_name"),
                        resultSet.getString("business_time_zone")),
                userId).stream().findFirst().orElse(new UserProfileDetails(null, null, null));
    }

    private static Instant instantOf(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
