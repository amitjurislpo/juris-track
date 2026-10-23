package com.jurislpo.juristrack.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The CSV file of the workday report, byte for byte as the old app's {@code src/app/api/reports/export/route.ts} wrote it: UTF-8 with
 * a byte order mark (so spreadsheet programs read it as UTF-8), CRLF after every line including the last, cells quoted and guarded
 * against formula injection by {@link #cell}. Durations as decimal hours ({@link #decimalHours}, the old {@code toDecimalHours}) and
 * times of day as {@code HH:mm} ({@link #wallClockTime}, the old {@code formatTime}), both checked against the golden cases.
 */
public final class CsvFormat {

    /** The line ending of every line, the last one included. */
    public static final String LINE_END = "\r\n";
    /** U+FEFF, written first. */
    public static final String BYTE_ORDER_MARK = "﻿";
    private static final Pattern FORMULA_START = Pattern.compile("^[=+\\-@\\t\\r]");
    private static final Pattern NEEDS_QUOTES = Pattern.compile("[\",\\n\\r]");
    private static final DateTimeFormatter CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private CsvFormat() {
    }

    /**
     * One cell (the old {@code csvCell}): text that a spreadsheet would run as a formula (starting with {@code =}, {@code +},
     * {@code -}, {@code @}, a tab or a carriage return) gets a leading {@code '}; then a cell containing a quote, comma or line break
     * is put in double quotes with its quotes doubled.
     */
    public static String cell(Object value) {
        String text = String.valueOf(value);
        String safeText = FORMULA_START.matcher(text).find() ? "'" + text : text;
        return NEEDS_QUOTES.matcher(safeText).find() ? "\"" + safeText.replace("\"", "\"\"") + "\"" : safeText;
    }

    /** The cells of one line, joined by commas (no line end). */
    public static String line(List<?> values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) line.append(',');
            line.append(cell(values.get(i)));
        }
        return line.toString();
    }

    /** The whole file: the byte order mark, then every line followed by CRLF, as UTF-8 bytes. */
    public static byte[] document(List<String> lines) {
        StringBuilder document = new StringBuilder(BYTE_ORDER_MARK);
        for (String line : lines) document.append(line).append(LINE_END);
        return document.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Seconds as hours with two decimals ({@code 30600} gives {@code "8.50"}), exactly as JavaScript's
     * {@code (seconds / 3600).toFixed(2)}: the double quotient rounded half up on its exact binary value ({@code 18} seconds is
     * {@code 0.005000000000000000104...} hours, so {@code "0.01"}), and "-0.00" for a tiny negative value.
     */
    public static String decimalHours(long seconds) {
        double hours = seconds / 3600.0;
        BigDecimal rounded = new BigDecimal(hours).setScale(2, RoundingMode.HALF_UP);
        String text = rounded.toPlainString();
        return hours < 0 && rounded.signum() == 0 ? "-" + text : text;
    }

    /** The time of day of an instant in a zone, {@code HH:mm} on a 24-hour clock ("09:05"). */
    public static String wallClockTime(Instant instant, ZoneId zone) {
        return CLOCK_TIME.format(instant.atZone(zone));
    }
}
