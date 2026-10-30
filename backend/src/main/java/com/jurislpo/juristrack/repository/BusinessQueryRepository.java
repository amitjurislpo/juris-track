package com.jurislpo.juristrack.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
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
 * The super admin's read queries over every business (system mode). A business's owner is its earliest-created ADMIN.
 */
@Repository
public class BusinessQueryRepository {

    /**
     * One business in the super admin's list.
     *
     * @param ownerName           the owner's "first last", or null when the business has no ADMIN (possible for a JurisOne business)
     * @param employeeCount       every user of the business, the owner included, active or not
     * @param activeEmployeeCount the active ones
     * @param isLinkedToJurisone  true when the business came from JurisOne (embedded)
     */
    public record BusinessRow(UUID id, String name, String timeZone, @JsonProperty("isSuspended") boolean isSuspended,
            @JsonProperty("isLinkedToJurisone") boolean isLinkedToJurisone, UUID ownerId, String ownerName, String ownerEmail, long employeeCount,
            long activeEmployeeCount, Instant createdAt) {}

    /** A business's owner as the super admin's detail shows them. */
    public record BusinessOwner(UUID id, String employeeCode, String firstName, String lastName, String email,
            @JsonProperty("isActive") boolean isActive, Instant lastLoginAt) {}

    /**
     * Filters of the super admin's list.
     *
     * @param isSuspended true = suspended only, false = active only, null = every business
     */
    public record BusinessFilter(Boolean isSuspended) {}

    /** The SQL behind each sort key of {@code GET /api/v1/businesses} ({@code %s} is the direction). */
    public static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "lower(business.name) %s",
            "createdAt", "business.created_at %s",
            "employeeCount", "employee_count %s",
            "status", "business.is_suspended %s");

    private static final String OWNER_JOIN = """
            LEFT JOIN LATERAL (
                SELECT candidate.id, candidate.employee_code, candidate.first_name, candidate.last_name, candidate.email, candidate.is_active,
                       candidate.last_login_at
                FROM users candidate
                WHERE candidate.business_id = business.id AND candidate.role = 'ADMIN'
                ORDER BY candidate.created_at, candidate.id
                LIMIT 1) owner ON true
            """;

    private static final String ROW_SELECT = """
            SELECT business.id, business.name, business.time_zone, business.is_suspended, business.jurisone_business_id IS NOT NULL AS is_linked_to_jurisone,
                   business.created_at, owner.id AS owner_id, NULLIF(TRIM(owner.first_name || ' ' || owner.last_name), '') AS owner_name,
                   owner.email AS owner_email,
                   (SELECT count(*) FROM users member WHERE member.business_id = business.id) AS employee_count,
                   (SELECT count(*) FROM users member WHERE member.business_id = business.id AND member.is_active) AS active_employee_count
            FROM businesses business
            """ + OWNER_JOIN;

    private static final RowMapper<BusinessRow> ROW_MAPPER = (resultSet, rowNumber) -> new BusinessRow(resultSet.getObject("id", UUID.class),
            resultSet.getString("name"), resultSet.getString("time_zone"), resultSet.getBoolean("is_suspended"),
            resultSet.getBoolean("is_linked_to_jurisone"), resultSet.getObject("owner_id", UUID.class), resultSet.getString("owner_name"),
            resultSet.getString("owner_email"), resultSet.getLong("employee_count"), resultSet.getLong("active_employee_count"),
            instantOf(resultSet, "created_at"));

    private final JdbcTemplate jdbcTemplate;

    public BusinessQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One page of businesses, searched by business name, owner name and owner email. */
    public List<BusinessRow> findPage(BusinessFilter filter, ListQuery listQuery) {
        List<Object> arguments = new ArrayList<>();
        String where = whereClause(filter, listQuery, arguments);
        String order = String.format(SORT_COLUMNS.get(listQuery.sort()), listQuery.isAscending() ? "ASC" : "DESC")
                + ", lower(business.name), business.id";
        arguments.add(listQuery.pageSize());
        arguments.add(listQuery.offset());
        return jdbcTemplate.query(ROW_SELECT + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", ROW_MAPPER, arguments.toArray());
    }

    public long count(BusinessFilter filter, ListQuery listQuery) {
        List<Object> arguments = new ArrayList<>();
        String where = whereClause(filter, listQuery, arguments);
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM businesses business " + OWNER_JOIN + where, Long.class, arguments.toArray());
        return total == null ? 0 : total;
    }

    private static String whereClause(BusinessFilter filter, ListQuery listQuery, List<Object> arguments) {
        StringBuilder where = new StringBuilder("WHERE true");
        if (filter.isSuspended() != null) {
            where.append(" AND business.is_suspended = ?");
            arguments.add(filter.isSuspended());
        }
        String searchPattern = listQuery.searchPattern();
        if (searchPattern != null) {
            where.append(" AND (business.name ILIKE ? OR COALESCE(owner.first_name || ' ' || owner.last_name, '') ILIKE ? OR COALESCE(owner.email, '') ILIKE ?)");
            for (int i = 0; i < 3; i++) arguments.add(searchPattern);
        }
        return where.toString();
    }

    /** One business's list row (with its counts); empty when unknown. */
    public Optional<BusinessRow> findRow(UUID businessId) {
        return jdbcTemplate.query(ROW_SELECT + "WHERE business.id = ?", ROW_MAPPER, businessId).stream().findFirst();
    }

    /** The business's owner (its earliest-created ADMIN), if any. */
    public Optional<BusinessOwner> findOwner(UUID businessId) {
        return jdbcTemplate.query("SELECT owner.* FROM (SELECT ? ::uuid AS id) business " + OWNER_JOIN + "WHERE owner.id IS NOT NULL",
                (resultSet, rowNumber) -> new BusinessOwner(resultSet.getObject("id", UUID.class), resultSet.getString("employee_code"),
                        resultSet.getString("first_name"), resultSet.getString("last_name"), resultSet.getString("email"),
                        resultSet.getBoolean("is_active"), instantOf(resultSet, "last_login_at")),
                businessId).stream().findFirst();
    }

    private static Instant instantOf(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
