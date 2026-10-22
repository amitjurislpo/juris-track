package com.jurislpo.juristrack.engine;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The calendar of a business in its time zone: which calendar date an instant falls on, when a day starts, wall-clock times to
 * instants, week and month starts. Ported literally from the old app's {@code src/lib/time/tz.ts} and checked against golden cases
 * generated from it ({@code golden/time-engine.json}). All calendar logic uses the business time zone, never the server's.
 *
 * <p><b>Date keys.</b> The old code's "date key" is an ISO calendar date string {@code YYYY-MM-DD} in the business time zone. Here a
 * date key is always a {@link LocalDate}; its {@code toString()} is exactly that string, and text from outside (query parameters,
 * request bodies) is read with {@link #parseDateKey} / {@link #isDateKey}, which accept exactly what the old {@code isDateKey} accepted.
 *
 * <p><b>Time zones.</b> Offsets and DST come from {@code java.time} (tzdb) instead of the JavaScript {@code Intl} API. Zone names are
 * resolved by {@link #findZone} the way {@code Intl} accepts them (see there), so a name valid in the old app is valid here.
 *
 * <p><b>Not ported</b> (on purpose): {@code toZonedInputValue} formats an instant for an {@code <input type="datetime-local">} and stays
 * in the frontend; {@code dateKeyToDb} / {@code dbToDateKey} were Prisma adapters, JPA maps a {@code date} column to {@link LocalDate}
 * directly. Display formatting and zone abbreviations (for example a CSV header "Times in IST") stay in the frontend; the CSV export's
 * zone abbreviation comes in a later phase.
 */
public final class BusinessCalendar {

    private static final Pattern DATE_KEY_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern LOCAL_DATE_TIME_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern OFFSET_PATTERN = Pattern.compile("[+-]\\d{2}(?::?\\d{2})?");
    private static final Map<String, ZoneId> ZONES_BY_LOWER_CASE_NAME = indexZonesByLowerCaseName();
    private static final long MILLISECONDS_PER_SECOND = 1000;
    private static final long SECONDS_PER_DAY = 86_400;

    private BusinessCalendar() {
    }

    private static Map<String, ZoneId> indexZonesByLowerCaseName() {
        Map<String, ZoneId> zones = new HashMap<>();
        // The three-letter ids (IST, PST, ...) are known to ICU too, as links to the same regions as in java.time.
        ZoneId.SHORT_IDS.keySet().forEach(name -> zones.put(name.toLowerCase(Locale.ROOT), ZoneId.of(name, ZoneId.SHORT_IDS)));
        ZoneId.getAvailableZoneIds().forEach(name -> zones.put(name.toLowerCase(Locale.ROOT), ZoneId.of(name)));
        return Map.copyOf(zones);
    }

    /**
     * Resolves a time zone name the way the old app's {@code Intl.DateTimeFormat} accepted it: any IANA name or link (also legacy ones
     * such as {@code Asia/Calcutta} and {@code US/Eastern}) ignoring case ({@code asia/kolkata} works), the three-letter ids such as
     * {@code IST} and {@code EST}, and a UTC offset written {@code +05}, {@code +0530} or {@code +05:30}. Rejected like in the old
     * app: {@code null}, {@code ""}, names with spaces around them, {@code Z}, {@code GMT+5}, {@code +5}.
     */
    public static Optional<ZoneId> findZone(String timeZone) {
        if (timeZone == null || timeZone.isEmpty()) {
            return Optional.empty();
        }
        if (OFFSET_PATTERN.matcher(timeZone).matches()) {
            try {
                return Optional.of(ZoneOffset.of(timeZone));
            } catch (DateTimeException e) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(ZONES_BY_LOWER_CASE_NAME.get(timeZone.toLowerCase(Locale.ROOT)));
    }

    /** Like {@link #findZone}, but an unknown name is an error ({@link IllegalArgumentException}), as {@code Intl} threw a RangeError. */
    public static ZoneId zoneOf(String timeZone) {
        return findZone(timeZone).orElseThrow(() -> new IllegalArgumentException("Unknown time zone: " + timeZone));
    }

    public static boolean isValidTimeZone(String timeZone) {
        return findZone(timeZone).isPresent();
    }

    /** Reads a date key ({@code YYYY-MM-DD}, a real calendar date); anything else is empty. */
    public static Optional<LocalDate> parseDateKey(String value) {
        if (value == null || !DATE_KEY_PATTERN.matcher(value).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static boolean isDateKey(String value) {
        return parseDateKey(value).isPresent();
    }

    /**
     * Offset of the zone from UTC at the given instant, in milliseconds. The old code read the wall clock with second precision and
     * subtracted the instant floored to whole seconds, which is exactly the zone offset in seconds times 1000.
     */
    public static long offsetMilliseconds(Instant instant, ZoneId zone) {
        return zone.getRules().getOffset(instant).getTotalSeconds() * MILLISECONDS_PER_SECOND;
    }

    /** Calendar date of an instant in the given zone. */
    public static LocalDate toDateKey(Instant instant, ZoneId zone) {
        return LocalDate.ofInstant(instant, zone);
    }

    /** The instant of midnight at the start of a date in the given zone. */
    public static Instant zonedTimeToUtc(LocalDate dateKey, ZoneId zone) {
        return zonedTimeToUtc(dateKey, zone, 0, 0, 0);
    }

    /**
     * The instant of a wall-clock time on a date in the given zone. Literal port of the old algorithm (not
     * {@code ZonedDateTime.of}): take the wall clock as if it were UTC, subtract the offset at that instant, and re-evaluate once with
     * the offset at the result in case the guess straddled a DST change. Consequences kept on purpose, as the golden cases show:
     * a time inside a spring-forward gap resolves one hour <em>earlier</em> (New York 2026-03-08 02:30 is 01:30 EST, 06:30Z), and an
     * ambiguous time in the autumn overlap resolves to the first (daylight) occurrence (2026-11-01 01:30 is 01:30 EDT, 05:30Z).
     * Out-of-range values roll over like {@code Date.UTC} (hour 24 is midnight of the next day).
     */
    public static Instant zonedTimeToUtc(LocalDate dateKey, ZoneId zone, int hour, int minute, int second) {
        long guessSeconds = dateKey.toEpochDay() * SECONDS_PER_DAY + hour * 3600L + minute * 60L + second;
        long guess = guessSeconds * MILLISECONDS_PER_SECOND;
        long offset = offsetMilliseconds(Instant.ofEpochMilli(guess), zone);
        long result = guess - offset;
        long offsetAtResult = offsetMilliseconds(Instant.ofEpochMilli(result), zone);
        if (offsetAtResult != offset) {
            result = guess - offsetAtResult;
        }
        return Instant.ofEpochMilli(result);
    }

    /** The instant a date starts in the given zone (old name: {@code startOfDayUtc}). */
    public static Instant startOfDay(LocalDate dateKey, ZoneId zone) {
        return zonedTimeToUtc(dateKey, zone);
    }

    public static LocalDate addDays(LocalDate dateKey, int days) {
        return dateKey.plusDays(days);
    }

    /** The Monday of the date's week (weeks start on Monday). */
    public static LocalDate startOfWeek(LocalDate dateKey) {
        int daysSinceMonday = dateKey.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        return addDays(dateKey, -daysSinceMonday);
    }

    public static LocalDate startOfMonth(LocalDate dateKey) {
        return dateKey.withDayOfMonth(1);
    }

    /** Calendar days from {@code from} to {@code to}; negative when {@code to} is earlier (old name: {@code diffDays}). */
    public static long daysBetween(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to);
    }

    /** Every date from {@code from} to {@code to}, both included; empty when {@code from} is after {@code to}. */
    public static List<LocalDate> eachDay(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.add(day);
        }
        return days;
    }

    /**
     * Reads an {@code <input type="datetime-local">} value ({@code 2026-10-01T09:30}, optionally with {@code :ss}) as a wall-clock
     * time in the given zone. Empty when malformed: not that exact shape, not a real date, hour above 23, minute or second above 59.
     */
    public static Optional<Instant> parseZonedDateTime(String value, ZoneId zone) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = LOCAL_DATE_TIME_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Optional<LocalDate> dateKey = parseDateKey(matcher.group(1));
        if (dateKey.isEmpty()) {
            return Optional.empty();
        }
        int hour = Integer.parseInt(matcher.group(2));
        int minute = Integer.parseInt(matcher.group(3));
        int second = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
        if (hour > 23 || minute > 59 || second > 59) {
            return Optional.empty();
        }
        return Optional.of(zonedTimeToUtc(dateKey.get(), zone, hour, minute, second));
    }
}
