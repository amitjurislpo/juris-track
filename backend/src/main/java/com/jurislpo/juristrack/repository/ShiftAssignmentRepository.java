package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.ShiftAssignment;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The assignment history of employees' shifts. */
public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignment, UUID> {

    List<ShiftAssignment> findByEmployeeIdIn(Collection<UUID> employeeIds);

    Optional<ShiftAssignment> findByEmployeeIdAndEffectiveFrom(UUID employeeId, LocalDate effectiveFrom);

    /**
     * How many active employees of the business have this shift as their latest assignment (the one that applies now or will apply
     * after a pending change).
     */
    @Query(value = """
            SELECT count(*) FROM (
                SELECT DISTINCT ON (assignment.employee_id) assignment.employee_id, assignment.shift_id
                FROM employee_shift_assignments assignment
                JOIN users person ON person.id = assignment.employee_id AND person.is_active
                WHERE assignment.business_id = :businessId
                ORDER BY assignment.employee_id, assignment.effective_from DESC) latest
            WHERE latest.shift_id = :shiftId""", nativeQuery = true)
    long countActiveEmployeesOfShift(@Param("businessId") UUID businessId, @Param("shiftId") UUID shiftId);

    /** Active people of the business (not the platform's super admin) with no assignment at all: they work the default shift. */
    @Query(value = """
            SELECT count(*) FROM users person
            WHERE person.business_id = :businessId AND person.is_active AND person.role <> 'SUPER_ADMIN'
              AND NOT EXISTS (SELECT 1 FROM employee_shift_assignments assignment WHERE assignment.employee_id = person.id)""", nativeQuery = true)
    long countActiveEmployeesWithoutAssignment(@Param("businessId") UUID businessId);
}
