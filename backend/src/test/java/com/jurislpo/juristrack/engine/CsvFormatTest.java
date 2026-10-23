package com.jurislpo.juristrack.engine;

import static com.jurislpo.juristrack.engine.GoldenFixture.eachCase;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * {@link CsvFormat}: the old {@code csvCell} quoting and formula guard, the file layout (byte order mark, CRLF after every line), and
 * the golden cases of the old {@code toDecimalHours} and {@code formatTime}.
 */
class CsvFormatTest {

    @TestFactory
    Stream<DynamicTest> toDecimalHours() {
        return eachCase("format", "toDecimalHours", testCase ->
                assertEquals(testCase.path("output").asText(), CsvFormat.decimalHours(testCase.path("input").asLong())));
    }

    /** The old formatTime cases with an instant (its "—" for a missing instant is display text; the CSV leaves the cell empty). */
    @TestFactory
    Stream<DynamicTest> formatTime() {
        return eachCase("format", "formatTime", testCase -> {
            String instant = GoldenFixture.text(testCase.path("input").path("instant"));
            if (instant == null) return;
            assertEquals(testCase.path("output").asText(), CsvFormat.wallClockTime(Instant.parse(instant),
                    BusinessCalendar.zoneOf(testCase.path("input").path("timeZone").asText())));
        });
    }

    @Test
    void decimalHoursRoundLikeJavaScriptToFixed() {
        assertEquals("0.00", CsvFormat.decimalHours(17));
        assertEquals("0.01", CsvFormat.decimalHours(18));
        assertEquals("1.50", CsvFormat.decimalHours(5399));
        assertEquals("-0.00", CsvFormat.decimalHours(-1));
        assertEquals("100.00", CsvFormat.decimalHours(360_000));
    }

    @Test
    void plainCellsStayAsTheyAre() {
        assertEquals("Esha Employee", CsvFormat.cell("Esha Employee"));
        assertEquals("8.50", CsvFormat.cell("8.50"));
        assertEquals("3", CsvFormat.cell(3));
        assertEquals("", CsvFormat.cell(""));
        assertEquals("Start (EDT)", CsvFormat.cell("Start (EDT)"));
    }

    @Test
    void cellsWithCommasQuotesOrLineBreaksAreQuoted() {
        assertEquals("\"Smith, John\"", CsvFormat.cell("Smith, John"));
        assertEquals("\"Say \"\"hi\"\"\"", CsvFormat.cell("Say \"hi\""));
        assertEquals("\"two\nlines\"", CsvFormat.cell("two\nlines"));
        assertEquals("\"carriage\rreturn\"", CsvFormat.cell("carriage\rreturn"));
    }

    @Test
    void formulasAreNeutralisedWithAQuote() {
        assertEquals("'=cmd", CsvFormat.cell("=cmd"));
        assertEquals("'+1", CsvFormat.cell("+1"));
        assertEquals("'-5", CsvFormat.cell("-5"));
        assertEquals("'@SUM(A1)", CsvFormat.cell("@SUM(A1)"));
        assertEquals("'\tTab", CsvFormat.cell("\tTab"));
        // a leading carriage return is guarded and then quoted, as the old csvCell did
        assertEquals("\"'\rx\"", CsvFormat.cell("\rx"));
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"", CsvFormat.cell("=HYPERLINK(\"http://x\",\"y\")"));
        // only the first character counts
        assertEquals("a=b", CsvFormat.cell("a=b"));
    }

    @Test
    void theFileStartsWithAByteOrderMarkAndEndsEveryLineWithCrLf() {
        byte[] document = CsvFormat.document(List.of(CsvFormat.line(List.of("Date", "Employee")), CsvFormat.line(List.of("2026-10-20", "=cmd"))));
        byte[] expected = "﻿Date,Employee\r\n2026-10-20,'=cmd\r\n".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, document);
        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, Arrays.copyOf(document, 3));
    }
}
