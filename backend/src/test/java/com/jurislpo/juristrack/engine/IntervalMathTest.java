package com.jurislpo.juristrack.engine;

import static com.jurislpo.juristrack.engine.GoldenFixture.eachCase;
import static com.jurislpo.juristrack.engine.GoldenFixture.instant;
import static com.jurislpo.juristrack.engine.GoldenFixture.interval;
import static com.jurislpo.juristrack.engine.GoldenFixture.intervals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** {@link IntervalMath} against the golden cases of the old {@code calc.ts}, plus the two worked examples of the old flow checks. */
class IntervalMathTest {

    private static Instant at(String time) {
        return Instant.parse("2026-10-01T" + time + ":00Z");
    }

    private static TimeInterval between(String start, String end) {
        return new TimeInterval(at(start), at(end));
    }

    @Test
    void eightHoursProductiveInANineHourDayWithOneHourOfBreaks() {
        WorkdayTotals totals = IntervalMath.computeTotals(
                between("09:00", "18:00"),
                List.of(between("09:00", "11:15"), between("11:30", "13:00"), between("13:45", "18:00")),
                List.of(between("11:15", "11:30"), between("13:00", "13:45")),
                at("18:00"));
        assertEquals(new WorkdayTotals(8 * 3600, 3600, 9 * 3600, 2), totals);
    }

    @Test
    void eightAndAHalfHoursProductiveWithAThirtyMinuteBreak() {
        WorkdayTotals totals = IntervalMath.computeTotals(
                between("09:00", "18:00"),
                List.of(between("09:00", "11:00"), between("11:30", "18:00")),
                List.of(between("11:00", "11:30")),
                at("18:00"));
        assertEquals(8 * 3600 + 1800, totals.productiveSeconds());
        assertEquals(1800, totals.breakSeconds());
    }

    @TestFactory
    Stream<DynamicTest> intervalSeconds() {
        return eachCase("calc", "intervalSeconds", testCase -> assertEquals(
                testCase.path("output").asLong(),
                IntervalMath.intervalSeconds(interval(testCase.path("input").path("interval")), instant(testCase.path("input").path("now")))));
    }

    @TestFactory
    Stream<DynamicTest> sumSeconds() {
        return eachCase("calc", "sumSeconds", testCase -> assertEquals(
                testCase.path("output").asLong(),
                IntervalMath.sumSeconds(intervals(testCase.path("input").path("intervals")), instant(testCase.path("input").path("now")))));
    }

    @TestFactory
    Stream<DynamicTest> closedSeconds() {
        return eachCase("calc", "closedSeconds", testCase -> assertEquals(
                testCase.path("output").asLong(),
                IntervalMath.closedSeconds(intervals(testCase.path("input").path("intervals")))));
    }

    @TestFactory
    Stream<DynamicTest> findOpenInterval() {
        return eachCase("calc", "openInterval", testCase -> {
            List<TimeInterval> intervals = intervals(testCase.path("input").path("intervals"));
            int expectedIndex = testCase.path("output").asInt();
            assertEquals(
                    expectedIndex < 0 ? null : intervals.get(expectedIndex),
                    IntervalMath.findOpenInterval(intervals).orElse(null));
        });
    }

    @TestFactory
    Stream<DynamicTest> computeTotals() {
        return eachCase("calc", "computeTotals", testCase -> {
            var input = testCase.path("input");
            var output = testCase.path("output");
            WorkdayTotals expected = new WorkdayTotals(
                    output.path("productiveSeconds").asLong(),
                    output.path("breakSeconds").asLong(),
                    output.path("spanSeconds").asLong(),
                    output.path("breakCount").asInt());
            assertEquals(expected, IntervalMath.computeTotals(
                    interval(input.path("workday")), intervals(input.path("workSessions")), intervals(input.path("breakSessions")),
                    instant(input.path("now"))), input.path("name").asText());
        });
    }
}
