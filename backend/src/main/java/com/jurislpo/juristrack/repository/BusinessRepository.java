package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.Business;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Businesses (one row per customer business; embedded in JurisOne, linked to the JurisOne business). */
public interface BusinessRepository extends JpaRepository<Business, UUID> {

    Optional<Business> findByJurisoneBusinessId(UUID jurisoneBusinessId);
}
