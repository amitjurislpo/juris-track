package com.jurislpo.juristrack.engine;

import static com.jurislpo.juristrack.engine.GoldenFixture.date;
import static com.jurislpo.juristrack.engine.GoldenFixture.eachCase;
import static com.jurislpo.juristrack.engine.GoldenFixture.instant;
import static com.jurislpo.juristrack.engine.GoldenFixture.list;
import static com.jurislpo.juristrack.engine.GoldenFixture.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * {@link BusinessCalendar} against the golden cases of the old {@code tz.ts} (Asia/Kolkata and America/New_York, the 2026 DST changes
 * on 2026-03-08 and 2026-11-01, night shifts across midnight, month ends and the leap day). {@code toZonedInputValue} is
 * frontend-only and is checked there.
 */
class BusinessCalendarTest {

    private static ZoneId zone(JsonNode input) {
        return BusinessCalendar.zoneOf(input.path("timeZone").asText());
    }

    @TestFactory
    Stream<DynamicTest> isValidTimeZone() {
        return eachCase("tz", "isValidTimeZone", testCase -> assertEquals(
                testCase.path("output").asBoolean(), BusinessCalendar.isValidTimeZone(testCase.path("input").asText())));
    }

    @Test
    void zoneOfRefusesAnUnknownZone() {
        assertThrows(IllegalArgumentException.class, () -> BusinessCalendar.zoneOf("Not/AZone"));
        assertEquals(ZoneId.of("Asia/Kolkata"), BusinessCalendar.zoneOf("asia/kolkata"));
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), BusinessCalendar.zoneOf("+0530"));
    }

    /** The old function also took non-text values (always false); a Java caller can only pass text or null, so those map to null. */
    @TestFactory
    Stream<DynamicTest> isDateKey() {
        return eachCase("tz", "isDateKey", testCase -> assertEquals(
                testCase.path("output").asBoolean(), BusinessCalendar.isDateKey(text(testCase.path("input")))));
    }

    @TestFactory
    Stream<DynamicTest> toDateKey() {
        return eachCase("tz", "toDateKey", testCase -> assertEquals(
                testCase.path("output").asText(),
                BusinessCalendar.toDateKey(instant(testCase.path("input").path("instant")), zone(testCase.path("input"))).toString()));
    }

    @TestFactory
    Stream<DynamicTest> offsetMilliseconds() {
        return eachCase("tz", "tzOffsetMs", testCase -> assertEquals(
                testCase.path("output").asLong(),
                BusinessCalendar.offsetMilliseconds(instant(testCase.path("input").path("instant")), zone(testCase.path("input")))));
    }

    /** The old default arguments (hour, minute, second = 0) are the missing JSON fields. */
    @TestFactory
    Stream<DynamicTest> zonedTimeToUtc() {
        return eachCase("tz", "zonedTimeToUtc", testCase -> {
            JsonNode input = testCase.path("input");
            assertEquals(instant(testCase.path("output")), BusinessCalendar.zonedTimeToUtc(
                    date(input.path("key")), zone(input), input.path("hour").asInt(0), input.path("minute").asInt(0), input.path("second").asInt(0)));
        });
    }

    @TestFactory
    Stream<DynamicTest> startOfDay() {
        return eachCase("tz", "startOfDayUtc", testCase -> assertEquals(
                instant(testCase.path("output")),
                BusinessCalendar.startOfDay(date(testCase.path("input").path("key")), zone(testCase.path("input")))));
    }

    @TestFactory
    Stream<DynamicTest> addDays() {
        return eachCase("tz", "addDays", testCase -> assertEquals(
                testCase.path("output").asText(),
                BusinessCalendar.addDays(date(testCase.path("input").path("key")), testCase.path("input").path("days").asInt()).toString()));
    }

    @TestFactory
    Stream<DynamicTest> startOfWeek() {
        return eachCase("tz", "startOfWeek", testCase -> assertEquals(
                testCase.path("output").asText(), BusinessCalendar.startOfWeek(date(testCase.path("input"))).toString()));
    }

    @TestFactory
    Stream<DynamicTest> startOfMonth() {
        return eachCase("tz", "startOfMonth", testCase -> assertEquals(
                testCase.path("output").asText(), BusinessCalendar.startOfMonth(date(testCase.path("input"))).toString()));
    }

    @TestFactory
    Stream<DynamicTest> daysBetween() {
        return eachCase("tz", "diffDays", testCase -> assertEquals(
                testCase.path("output").asLong(),
                BusinessCalendar.daysBetween(date(testCase.path("input").path("from")), date(testCase.path("input").path("to")))));
    }

    @TestFactory
    Stream<DynamicTest> eachDay() {
        return eachCase("tz", "eachDay", testCase -> assertEquals(
                list(testCase.path("output"), JsonNode::asText),
                BusinessCalendar.eachDay(date(testCase.path("input").path("from")), date(testCase.path("input").path("to")))
                        .stream().map(LocalDate::toString).toList()));
    }

    @TestFactory
    Stream<DynamicTest> parseZonedDateTime() {
        return eachCase("tz", "parseZonedDateTime", testCase -> assertEquals(
                instant(testCase.path("output")),
                BusinessCalendar.parseZonedDateTime(testCase.path("input").path("value").asText(), zone(testCase.path("input"))).orElse(null)));
    }

    /** No Java method: JPA maps a {@code date} column to {@link LocalDate}. These check that the mapping means the same thing. */
    @TestFactory
    Stream<DynamicTest> dateKeyToDatabaseValue() {
        return eachCase("tz", "dateKeyToDb", testCase -> assertEquals(
                instant(testCase.path("output")), date(testCase.path("input")).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    @TestFactory
    Stream<DynamicTest> databaseValueToDateKey() {
        return eachCase("tz", "dbToDateKey", testCase -> assertEquals(
                testCase.path("output").asText(), LocalDate.ofInstant(instant(testCase.path("input")), ZoneOffset.UTC).toString()));
    }

    @Test
    void aNightShiftBelongsToTheDayItStartedOn() {
        ZoneId kolkata = BusinessCalendar.zoneOf("Asia/Kolkata");
        Instant start = BusinessCalendar.parseZonedDateTime("2026-10-01T22:00", kolkata).orElseThrow();
        Instant end = BusinessCalendar.parseZonedDateTime("2026-10-02T06:00", kolkata).orElseThrow();
        assertEquals(LocalDate.parse("2026-10-01"), BusinessCalendar.toDateKey(start, kolkata));
        assertEquals(LocalDate.parse("2026-10-02"), BusinessCalendar.toDateKey(end, kolkata));
        assertEquals(8 * 3600, IntervalMath.intervalSeconds(new TimeInterval(start, end), end));
    }
}
