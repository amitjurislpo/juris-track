package com.jurislpo.juristrack.engine;

import static com.jurislpo.juristrack.engine.GoldenFixture.eachCase;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * {@link ZoneNames} against the golden cases of the old {@code zones.ts} ({@code zoneAbbr}, {@code zoneLabel}, {@code zoneLongName}:
 * India, New York around both DST changes, UTC, London, Dubai, Singapore, Sydney, Kathmandu), plus the cases where java.time and
 * Node's Intl differ and the port follows Node.
 */
class ZoneNamesTest {

    private static ZoneId zoneOf(JsonNode testCase) {
        return BusinessCalendar.zoneOf(testCase.path("input").path("timeZone").asText());
    }

    private static Instant instantOf(JsonNode testCase) {
        return Instant.parse(testCase.path("input").path("instant").asText());
    }

    @TestFactory
    Stream<DynamicTest> zoneAbbr() {
        return eachCase("zones", "zoneAbbr", testCase ->
                assertEquals(testCase.path("output").asText(), ZoneNames.zoneAbbreviation(zoneOf(testCase), instantOf(testCase))));
    }

    @TestFactory
    Stream<DynamicTest> zoneLongName() {
        return eachCase("zones", "zoneLongName", testCase ->
                assertEquals(testCase.path("output").asText(), ZoneNames.zoneLongName(zoneOf(testCase), instantOf(testCase))));
    }

    @TestFactory
    Stream<DynamicTest> zoneLabel() {
        return eachCase("zones", "zoneLabel", testCase ->
                assertEquals(testCase.path("output").asText(), ZoneNames.zoneLabel(zoneOf(testCase), instantOf(testCase))));
    }

    @Test
    void javaTimesOwnAbbreviationsAreNotUsed() {
        Instant winter = Instant.parse("2026-01-15T12:00:00Z");
        // java.time says SGT, NPT and PKT here; ICU writes the offset, so the old app showed the initials of the long name
        assertEquals("SST", ZoneNames.zoneAbbreviation(ZoneId.of("Asia/Singapore"), winter));
        assertEquals("NT", ZoneNames.zoneAbbreviation(ZoneId.of("Asia/Kathmandu"), winter));
        assertEquals("PST", ZoneNames.zoneAbbreviation(ZoneId.of("Asia/Karachi"), winter));
        assertEquals("CEST", ZoneNames.zoneAbbreviation(ZoneId.of("Europe/Paris"), winter));
        // ICU writes "GMT+0" at offset zero too: Lisbon in winter is the initials of "Western European Standard Time"
        assertEquals("WEST", ZoneNames.zoneAbbreviation(ZoneId.of("Europe/Lisbon"), winter));
        assertEquals("GMT", ZoneNames.zoneAbbreviation(ZoneId.of("Africa/Abidjan"), winter));
    }

    @Test
    void theNorthAmericanAbbreviationsComeFromEnglishCldr() {
        Instant summer = Instant.parse("2026-07-15T12:00:00Z");
        assertEquals("PDT", ZoneNames.zoneAbbreviation(ZoneId.of("America/Los_Angeles"), summer));
        assertEquals("CDT", ZoneNames.zoneAbbreviation(ZoneId.of("America/Chicago"), summer));
        assertEquals("MST", ZoneNames.zoneAbbreviation(ZoneId.of("America/Phoenix"), summer));
        assertEquals("AKDT", ZoneNames.zoneAbbreviation(ZoneId.of("America/Anchorage"), summer));
        assertEquals("ADT", ZoneNames.zoneAbbreviation(ZoneId.of("America/Halifax"), summer));
        // Honolulu has its own HST; the other Hawaii-Aleutian zone is HADT in summer, as in Node
        assertEquals("HST", ZoneNames.zoneAbbreviation(ZoneId.of("Pacific/Honolulu"), summer));
        assertEquals("HADT", ZoneNames.zoneAbbreviation(ZoneId.of("America/Adak"), summer));
    }

    @Test
    void britishSummerTimeIsLondonsOwnName() {
        Instant summer = Instant.parse("2026-07-15T12:00:00Z");
        assertEquals("British Summer Time (BST)", ZoneNames.zoneLabel(ZoneId.of("Europe/London"), summer));
        // ICU has no name for Jersey's summer time and writes the offset
        assertEquals("GMT+01:00", ZoneNames.zoneLongName(ZoneId.of("Europe/Jersey"), summer));
        assertEquals("G", ZoneNames.zoneAbbreviation(ZoneId.of("Europe/Jersey"), summer));
    }

    @Test
    void aZoneGivenAsAnOffsetIsNamedLikeIcuNamesIt() {
        Instant instant = Instant.parse("2026-01-15T12:00:00Z");
        assertEquals("GMT+05:30", ZoneNames.zoneLongName(BusinessCalendar.zoneOf("+05:30"), instant));
        assertEquals("G", ZoneNames.zoneAbbreviation(BusinessCalendar.zoneOf("+05:30"), instant));
        assertEquals("Greenwich Mean Time (GMT)", ZoneNames.zoneLabel(BusinessCalendar.zoneOf("+00:00"), instant));
    }

    @Test
    void legacyLinksHaveTheSameNamesAsTheirZone() {
        Instant instant = Instant.parse("2026-01-15T12:00:00Z");
        assertEquals("IST", ZoneNames.zoneAbbreviation(BusinessCalendar.zoneOf("Asia/Calcutta"), instant));
        assertEquals("EST", ZoneNames.zoneAbbreviation(BusinessCalendar.zoneOf("US/Eastern"), instant));
    }
}
