package com.jurislpo.juristrack.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every event in every state: the {@link TimeStateMachine} table of the old time-tracking service, with D5's reopen after the end. */
class TimeStateMachineTest {

    @ParameterizedTest(name = "{1} while {0} -> {2}")
    @CsvSource({
        "NOT_STARTED, START,  START_WORKDAY",
        "NOT_STARTED, BREAK,  REJECTED_WORKDAY_NOT_STARTED",
        "NOT_STARTED, RESUME, REJECTED_NOT_ON_BREAK",
        "NOT_STARTED, END,    REJECTED_WORKDAY_NOT_STARTED",
        "WORKING,     START,  ALREADY_APPLIED",
        "WORKING,     BREAK,  START_BREAK",
        "WORKING,     RESUME, ALREADY_APPLIED",
        "WORKING,     END,    END_WORKDAY",
        "ON_BREAK,    START,  ALREADY_APPLIED",
        "ON_BREAK,    BREAK,  ALREADY_APPLIED",
        "ON_BREAK,    RESUME, RESUME_WORK",
        "ON_BREAK,    END,    REJECTED_BREAK_IN_PROGRESS",
        "DAY_ENDED,   START,  REOPEN_WORKDAY",
        "DAY_ENDED,   BREAK,  REJECTED_WORKDAY_NOT_STARTED",
        "DAY_ENDED,   RESUME, REJECTED_NOT_ON_BREAK",
        "DAY_ENDED,   END,    ALREADY_APPLIED",
    })
    void decidesEveryEventInEveryState(TrackingStatus status, TimeEventType event, TimeTransition expected) {
        assertEquals(expected, TimeStateMachine.decide(status, event));
    }

    @ParameterizedTest
    @CsvSource({"START, START", "' break ', BREAK", "Resume, RESUME", "end, END"})
    void readsEventNamesInAnyCase(String name, TimeEventType expected) {
        assertEquals(expected, TimeEventType.fromName(name).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource(value = {"LUNCH", "''", "NULL"}, nullValues = "NULL")
    void rejectsUnknownEventNames(String name) {
        assertEquals(Optional.empty(), TimeEventType.fromName(name));
    }
}
