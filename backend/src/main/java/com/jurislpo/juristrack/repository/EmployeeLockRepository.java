package com.jurislpo.juristrack.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Serializes the time tracking of one employee: {@code SELECT ... FOR UPDATE} on the employee's row inside the caller's transaction
 * (through the transaction's own connection), so two requests of the same employee run one after the other while other employees
 * are never blocked. Ported from the old app's {@code withEmployeeLock}.
 */
@Repository
public class EmployeeLockRepository {

    /**
     * The locked employee and what time tracking needs to know about them.
     *
     * @param employeeId        the user
     * @param businessId        their business
     * @param isActive          false once deactivated
     * @param managerId         their manager now (copied onto a new workday), or null
     * @param businessTimeZone  the business time zone (IANA id): it decides a new workday's date
     * @param staleWorkdayHours the business's threshold for "not ended"
     */
    public record LockedEmployee(UUID employeeId, UUID businessId, boolean isActive, UUID managerId, String businessTimeZone,
            int staleWorkdayHours) {}

    @PersistenceContext
    private EntityManager entityManager;

    /** Locks the employee's row until the transaction ends; empty when there is no such user in a business (or it is not visible). */
    public Optional<LockedEmployee> lockEmployee(UUID employeeId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT person.id, person.business_id, person.is_active, person.manager_id, business.time_zone, business.stale_workday_hours
                FROM users person
                JOIN businesses business ON business.id = person.business_id
                WHERE person.id = :employeeId
                FOR UPDATE OF person""")
                .setParameter("employeeId", employeeId)
                .getResultList();
        return rows.stream().findFirst().map(row -> new LockedEmployee((UUID) row[0], (UUID) row[1], (Boolean) row[2], (UUID) row[3],
                (String) row[4], ((Number) row[5]).intValue()));
    }
}
