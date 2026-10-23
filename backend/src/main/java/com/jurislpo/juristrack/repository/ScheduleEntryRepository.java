package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.ScheduleEntry;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The roster: per-date entries of employees. */
public interface ScheduleEntryRepository extends JpaRepository<ScheduleEntry, UUID> {

    List<ScheduleEntry> findByEmployeeIdInAndEntryDateBetween(Collection<UUID> employeeIds, LocalDate from, LocalDate to);

    Optional<ScheduleEntry> findByEmployeeIdAndEntryDate(UUID employeeId, LocalDate entryDate);
}
