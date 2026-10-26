package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.Business;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Businesses (one row per customer business; embedded in JurisOne, linked to the JurisOne business). */
public interface BusinessRepository extends JpaRepository<Business, UUID> {

    Optional<Business> findByJurisoneBusinessId(UUID jurisoneBusinessId);

    /** The business with its row locked until the transaction ends, so two roster syncs of one business run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT business FROM Business business WHERE business.id = :businessId")
    Optional<Business> findByIdForUpdate(@Param("businessId") UUID businessId);
}
