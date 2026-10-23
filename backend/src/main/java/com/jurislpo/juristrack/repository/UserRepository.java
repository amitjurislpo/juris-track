package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Users. Sign-in and provisioning run in system mode (every business visible); business requests see only their own rows (RLS). */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Emails are stored lower-cased and are unique across every business. */
    Optional<User> findByEmail(String email);

    Optional<User> findByJurisoneEmployeeId(UUID jurisoneEmployeeId);

    Optional<User> findByBusinessIdAndJurisoneEmployeeId(UUID businessId, UUID jurisoneEmployeeId);
}
