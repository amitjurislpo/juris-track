package com.jurislpo.juristrack.engine;

import java.time.LocalDate;

/**
 * Resolves the date range of a history or report request from its query parameters ({@code range}, {@code from}, {@code to}),
 * relative to "today" in the business time zone. Ported literally from the old app's {@code src/lib/time/ranges.ts} and checked
 * against golden cases generated from it ({@code golden/time-engine.json}). Invalid input never fails: it falls back to the default.
 *
 * <p>The old code accepted a repeated query parameter and used its first value; a controller passes that first value here. The
 * preset labels ("This week", ...) are display text and stay in the frontend.
 */
public final class DateRanges {

    /** Maximum span of a single query in days (both ends included), to keep reports bounded. */
    public static final int MAX_RANGE_DAYS = 366;

    private DateRanges() {
    }

    /** {@link #resolveRange(String, String, String, LocalDate, RangePreset)} with {@code today} as the default preset. */
    public static DateRange resolveRange(String range, String from, String to, LocalDate today) {
        return resolveRange(range, from, to, today, RangePreset.TODAY);
    }

    /**
     * The range for the {@code range} preset key; an unknown or missing key uses {@code fallback}. For {@code custom}: an invalid
     * {@code from} becomes the start of this week, an invalid {@code to} becomes today, reversed ends are swapped, and a span longer
     * than {@link #MAX_RANGE_DAYS} is cut at the start (the end is kept).
     */
    public static DateRange resolveRange(String range, String from, String to, LocalDate today, RangePreset fallback) {
        RangePreset preset = RangePreset.fromKey(range).orElse(fallback);
        return switch (preset) {
            case TODAY -> new DateRange(preset, today, today);
            case YESTERDAY -> {
                LocalDate yesterday = BusinessCalendar.addDays(today, -1);
                yield new DateRange(preset, yesterday, yesterday);
            }
            case WEEK -> new DateRange(preset, BusinessCalendar.startOfWeek(today), today);
            case MONTH -> new DateRange(preset, BusinessCalendar.startOfMonth(today), today);
            case CUSTOM -> resolveCustomRange(from, to, today);
        };
    }

    private static DateRange resolveCustomRange(String fromText, String toText, LocalDate today) {
        LocalDate from = BusinessCalendar.parseDateKey(fromText).orElse(BusinessCalendar.startOfWeek(today));
        LocalDate to = BusinessCalendar.parseDateKey(toText).orElse(today);
        if (from.isAfter(to)) {
            LocalDate earlier = to;
            to = from;
            from = earlier;
        }
        LocalDate earliestFrom = BusinessCalendar.addDays(to, -(MAX_RANGE_DAYS - 1));
        if (from.isBefore(earliestFrom)) {
            from = earliestFrom;
        }
        return new DateRange(RangePreset.CUSTOM, from, to);
    }
}
