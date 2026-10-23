package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.Shift;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Shift templates of a business. */
public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    List<Shift> findByBusinessId(UUID businessId);

    @Query("SELECT shift FROM Shift shift WHERE shift.businessId = :businessId AND shift.isDefault = TRUE")
    Optional<Shift> findDefault(@Param("businessId") UUID businessId);

    @Query("SELECT COUNT(shift) > 0 FROM Shift shift WHERE shift.businessId = :businessId AND LOWER(shift.name) = LOWER(:name)"
            + " AND (:excludedShiftId IS NULL OR shift.id <> :excludedShiftId)")
    boolean existsByName(@Param("businessId") UUID businessId, @Param("name") String name, @Param("excludedShiftId") UUID excludedShiftId);
}
