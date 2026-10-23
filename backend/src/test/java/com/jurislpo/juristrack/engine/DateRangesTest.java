package com.jurislpo.juristrack.engine;

import static com.jurislpo.juristrack.engine.GoldenFixture.eachCase;
import static com.jurislpo.juristrack.engine.GoldenFixture.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** {@link DateRanges} against the golden cases of the old {@code ranges.ts}: every preset, invalid input, swapped ends, the 366-day cap. */
class DateRangesTest {

    /** A query parameter as the old code read it: a repeated parameter (JSON array) counts with its first value. */
    private static String firstValue(JsonNode parameter) {
        return parameter.isArray() ? text(parameter.path(0)) : text(parameter);
    }

    @Test
    void maximumSpanIsTheSameAsInTheOldApp() {
        assertEquals(GoldenFixture.root().path("ranges").path("maxRangeDays").asInt(), DateRanges.MAX_RANGE_DAYS);
    }

    @TestFactory
    Stream<DynamicTest> resolveRange() {
        return eachCase("ranges", "resolveRange", testCase -> {
            JsonNode input = testCase.path("input");
            JsonNode parameters = input.path("params");
            LocalDate today = LocalDate.parse(input.path("today").asText());
            String range = firstValue(parameters.path("range"));
            String from = firstValue(parameters.path("from"));
            String to = firstValue(parameters.path("to"));
            String fallback = text(input.path("fallback"));
            DateRange resolved = fallback == null
                    ? DateRanges.resolveRange(range, from, to, today)
                    : DateRanges.resolveRange(range, from, to, today, RangePreset.fromKey(fallback).orElseThrow());

            JsonNode output = testCase.path("output");
            assertEquals(output.path("preset").asText(), resolved.preset().key());
            assertEquals(output.path("from").asText(), resolved.from().toString());
            assertEquals(output.path("to").asText(), resolved.to().toString());
        });
    }
}
