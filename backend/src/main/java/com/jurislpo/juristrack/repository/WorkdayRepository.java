package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.Workday;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Workdays (one per employee and date; at most one open per employee, keyed by {@code active_employee_id}). */
public interface WorkdayRepository extends JpaRepository<Workday, UUID> {

    /** The employee's open workday (WORKING or ON_BREAK), from whichever date it started. */
    Optional<Workday> findByActiveEmployeeId(UUID employeeId);

    Optional<Workday> findByEmployeeIdAndWorkDate(UUID employeeId, LocalDate workDate);

    /** The employees of the business who have an open workday (WORKING or ON_BREAK). */
    @Query("SELECT workday.employeeId FROM Workday workday WHERE workday.businessId = :businessId AND workday.activeEmployeeId IS NOT NULL")
    List<UUID> findEmployeeIdsWithOpenWorkday(@Param("businessId") UUID businessId);

    /** The employee's workdays dated {@code from} to {@code to} (both included). */
    List<Workday> findByEmployeeIdAndWorkDateBetween(UUID employeeId, LocalDate from, LocalDate to);

    /**
     * In one query, the workdays of these employees that can be "current": still open (from any date) or dated (shift date) from
     * {@code from} to {@code to}, a few days around today: the live board picks each person's shift occurrence among them.
     */
    @Query("SELECT workday FROM Workday workday WHERE workday.employeeId IN :employeeIds"
            + " AND (workday.activeEmployeeId IS NOT NULL OR workday.workDate BETWEEN :from AND :to)")
    List<Workday> findOpenOrDatedBetweenByEmployeeIds(@Param("employeeIds") Collection<UUID> employeeIds, @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
