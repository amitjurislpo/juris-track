package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.BreakSession;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Break sessions of workdays. */
public interface BreakSessionRepository extends JpaRepository<BreakSession, UUID> {

    List<BreakSession> findByWorkdayIdOrderByStartedAtAsc(UUID workdayId);

    List<BreakSession> findByWorkdayIdInOrderByStartedAtAsc(Collection<UUID> workdayIds);
}
