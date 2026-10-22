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

    /** The employee's workdays dated {@code from} to {@code to} (both included). */
    List<Workday> findByEmployeeIdAndWorkDateBetween(UUID employeeId, LocalDate from, LocalDate to);

    /** In one query, the workdays of these employees that are dated {@code date} or still open (from any date): the live board. */
    @Query("SELECT workday FROM Workday workday WHERE workday.employeeId IN :employeeIds"
            + " AND (workday.workDate = :date OR workday.activeEmployeeId IS NOT NULL)")
    List<Workday> findDatedOrOpenByEmployeeIds(@Param("employeeIds") Collection<UUID> employeeIds, @Param("date") LocalDate date);
}
