package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.engine.SegmentType;
import com.jurislpo.juristrack.engine.TimelineSegment;
import com.jurislpo.juristrack.engine.TrackingStatus;
import com.jurislpo.juristrack.engine.WorkdaySnapshot;
import com.jurislpo.juristrack.engine.WorkdayTimeline;
import com.jurislpo.juristrack.entity.BreakSession;
import com.jurislpo.juristrack.entity.WorkSession;
import com.jurislpo.juristrack.entity.Workday;
import com.jurislpo.juristrack.entity.WorkdayStatus;
import com.jurislpo.juristrack.repository.BreakSessionRepository;
import com.jurislpo.juristrack.repository.WorkSessionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Turns stored workdays and their sessions into {@link WorkdaySnapshot}s (the time state, the workday detail, the history). */
@Component
class WorkdaySnapshots {

    private final WorkSessionRepository workSessionRepository;
    private final BreakSessionRepository breakSessionRepository;

    WorkdaySnapshots(WorkSessionRepository workSessionRepository, BreakSessionRepository breakSessionRepository) {
        this.workSessionRepository = workSessionRepository;
        this.breakSessionRepository = breakSessionRepository;
    }

    /** The workday as of {@code now}, with its intervals read from the database (inside the caller's transaction). */
    WorkdaySnapshot snapshotOf(Workday workday, Instant now, int staleWorkdayHours) {
        return snapshotOf(workday, workSessionRepository.findByWorkdayIdOrderByStartedAtAsc(workday.getId()),
                breakSessionRepository.findByWorkdayIdOrderByStartedAtAsc(workday.getId()), now, staleWorkdayHours);
    }

    /** The workday as of {@code now} from intervals the caller already loaded. */
    static WorkdaySnapshot snapshotOf(Workday workday, List<WorkSession> workSessions, List<BreakSession> breakSessions, Instant now,
            int staleWorkdayHours) {
        return WorkdayTimeline.snapshot(workday.getId(), workday.getWorkDate(), statusOf(workday.getStatus()), workday.getStartedAt(),
                workday.getEndedAt(), workSegments(workSessions), breakSegments(breakSessions), now, staleWorkdayHours);
    }

    /** The stored status as the API shows it: COMPLETED is DAY_ENDED. */
    static TrackingStatus statusOf(WorkdayStatus status) {
        return switch (status) {
            case WORKING -> TrackingStatus.WORKING;
            case ON_BREAK -> TrackingStatus.ON_BREAK;
            case COMPLETED -> TrackingStatus.DAY_ENDED;
        };
    }

    static List<TimelineSegment> workSegments(List<WorkSession> workSessions) {
        return workSessions.stream()
                .map(session -> new TimelineSegment(session.getId(), SegmentType.WORK, session.getStartedAt(), session.getEndedAt())).toList();
    }

    static List<TimelineSegment> breakSegments(List<BreakSession> breakSessions) {
        return breakSessions.stream()
                .map(session -> new TimelineSegment(session.getId(), SegmentType.BREAK, session.getStartedAt(), session.getEndedAt())).toList();
    }
}
