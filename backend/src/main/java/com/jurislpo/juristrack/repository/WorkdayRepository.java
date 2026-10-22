package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.Workday;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Workdays (one per employee and date; at most one open per employee, keyed by {@code active_employee_id}). */
public interface WorkdayRepository extends JpaRepository<Workday, UUID> {

    /** The employee's open workday (WORKING or ON_BREAK), from whichever date it started. */
    Optional<Workday> findByActiveEmployeeId(UUID employeeId);

    Optional<Workday> findByEmployeeIdAndWorkDate(UUID employeeId, LocalDate workDate);

    /** The employee's workdays dated {@code from} to {@code to} (both included). */
    List<Workday> findByEmployeeIdAndWorkDateBetween(UUID employeeId, LocalDate from, LocalDate to);
}
