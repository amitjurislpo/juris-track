package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.WorkSession;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Work sessions (productive intervals) of workdays. */
public interface WorkSessionRepository extends JpaRepository<WorkSession, UUID> {

    List<WorkSession> findByWorkdayIdOrderByStartedAtAsc(UUID workdayId);

    List<WorkSession> findByWorkdayIdInOrderByStartedAtAsc(Collection<UUID> workdayIds);
}
