package com.jurislpo.juristrack.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.entity.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read queries of the manager's Team tab: the people of a scope (one query for all of them, never one per person) and the owner of
 * a workday. Every query names the business explicitly as well; row-level security already hides the others.
 */
@Repository
public class TeamQueryRepository {

    /**
     * A person as the Team tab shows them.
     *
     * @param managerName the manager's "first last", or null without a manager
     */
    public record TeamMemberEmployee(UUID id, String employeeCode, String firstName, String lastName, String email, Role role,
            @JsonProperty("isActive") boolean isActive, UUID managerId, String managerName) {}

    private static final String MEMBER_SELECT = """
            SELECT person.id, person.employee_code, person.first_name, person.last_name, person.email, person.role::text AS role, person.is_active,
                   person.manager_id, NULLIF(TRIM(manager.first_name || ' ' || manager.last_name), '') AS manager_name
            FROM users person
            LEFT JOIN users manager ON manager.id = person.manager_id
            WHERE person.business_id = :businessId AND person.role <> 'SUPER_ADMIN'
            """;

    private static final RowMapper<TeamMemberEmployee> MEMBER_MAPPER = (resultSet, rowNumber) -> new TeamMemberEmployee(
            resultSet.getObject("id", UUID.class), resultSet.getString("employee_code"), resultSet.getString("first_name"),
            resultSet.getString("last_name"), resultSet.getString("email"), Role.valueOf(resultSet.getString("role")),
            resultSet.getBoolean("is_active"), resultSet.getObject("manager_id", UUID.class), resultSet.getString("manager_name"));

    /** Active people, and deactivated people who still have an open workday (there should be none: deactivation closes it). */
    private static final String ON_THE_BOARD = " AND (person.is_active OR EXISTS (SELECT 1 FROM workdays open_workday"
            + " WHERE open_workday.active_employee_id = person.id))";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TeamQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Everybody of the business on the live board except {@code viewerId} (the owner's "all" scope). */
    public List<TeamMemberEmployee> findBoardMembersOfBusiness(UUID businessId, UUID viewerId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("businessId", businessId);
        parameters.put("viewerId", viewerId);
        return jdbcTemplate.query(MEMBER_SELECT + " AND person.id <> :viewerId" + ON_THE_BOARD, parameters, MEMBER_MAPPER);
    }

    /** The given people of the business on the live board (a manager's scope); empty without ids. */
    public List<TeamMemberEmployee> findBoardMembers(UUID businessId, Collection<UUID> employeeIds) {
        if (employeeIds.isEmpty()) return List.of();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("businessId", businessId);
        parameters.put("employeeIds", employeeIds);
        return jdbcTemplate.query(MEMBER_SELECT + " AND person.id IN (:employeeIds)" + ON_THE_BOARD, parameters, MEMBER_MAPPER);
    }

    /** One person of the business, active or not; empty when unknown or of another business. */
    public Optional<TeamMemberEmployee> findMember(UUID businessId, UUID employeeId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("businessId", businessId);
        parameters.put("employeeId", employeeId);
        return jdbcTemplate.query(MEMBER_SELECT + " AND person.id = :employeeId", parameters, MEMBER_MAPPER).stream().findFirst();
    }

    /**
     * Whether another workday of the employee overlaps the time from {@code from} to {@code until} (touching is not overlapping); a
     * workday that is still open lasts until {@code now}.
     */
    public boolean existsOverlappingWorkday(UUID employeeId, UUID excludedWorkdayId, Instant from, Instant until, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("employeeId", employeeId);
        parameters.put("excludedWorkdayId", excludedWorkdayId);
        parameters.put("fromTime", Timestamp.from(from));
        parameters.put("untilTime", Timestamp.from(until));
        parameters.put("nowTime", Timestamp.from(now));
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM workdays other
                               WHERE other.employee_id = :employeeId AND other.id <> :excludedWorkdayId
                                 AND other.started_at < :untilTime AND COALESCE(other.ended_at, :nowTime) > :fromTime)""", parameters, Boolean.class));
    }

    /** Whose workday this is; empty when unknown or of another business. Read without loading the workday into the persistence context. */
    public Optional<UUID> findWorkdayEmployeeId(UUID businessId, UUID workdayId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("businessId", businessId);
        parameters.put("workdayId", workdayId);
        return jdbcTemplate.query("SELECT employee_id FROM workdays WHERE id = :workdayId AND business_id = :businessId", parameters,
                (resultSet, rowNumber) -> resultSet.getObject("employee_id", UUID.class)).stream().findFirst();
    }
}
