package com.jurislpo.juristrack.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which shift occurrence an instant belongs to, and the extra time of a workday. Business zone Asia/Kolkata (UTC+05:30) unless a test
 * says otherwise.
 */
class ShiftAttributionTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ShiftSchedule US = new ShiftSchedule(null, "US shift", LocalTime.of(19, 30), LocalTime.of(4, 30), 180, 180);
    private static final ShiftSchedule UK = new ShiftSchedule(null, "UK shift", LocalTime.of(14, 0), LocalTime.of(23, 0), 180, 180);
    private static final LocalDate D = LocalDate.of(2026, 10, 5);

    private static ShiftAttribution at(String utc, ZoneId zone, EmployeeSchedule schedule) {
        return ShiftAttribution.of(Instant.parse(utc), zone, schedule);
    }

    private static EmployeeSchedule assigned(ShiftSchedule shift) {
        return new EmployeeSchedule(ShiftSchedule.calendarDay()).assign(LocalDate.of(2026, 1, 1), shift);
    }

    @Test
    void theDefaultShiftIsTheCalendarDay() {
        EmployeeSchedule schedule = new EmployeeSchedule(ShiftSchedule.calendarDay());
        ShiftAttribution lateEvening = at("2026-10-05T18:29:00Z", INDIA, schedule); // 23:59 on D
        ShiftAttribution afterMidnight = at("2026-10-05T18:30:00Z", INDIA, schedule); // 00:00 on D+1
        assertThat(lateEvening.occurrence().date()).isEqualTo(D);
        assertThat(afterMidnight.occurrence().date()).isEqualTo(D.plusDays(1));
        assertThat(lateEvening.isOutsideShift()).isFalse();
        assertThat(lateEvening.isOffDay()).isFalse();
        assertThat(WorkdayShift.of(lateEvening.occurrence(), INDIA).scheduledSeconds()).isEqualTo(24 * 3600);
        assertThat(ShiftSchedule.calendarDay().scheduledMinutes()).isEqualTo(24 * 60);
    }

    @Test
    void aUsShiftIsOneNineHourOccurrenceAcrossMidnight() {
        EmployeeSchedule schedule = assigned(US);
        ShiftOccurrence occurrence = at("2026-10-05T14:00:00Z", INDIA, schedule).occurrence(); // 19:30 on D
        assertThat(occurrence.date()).isEqualTo(D);
        assertThat(occurrence.scheduledStartAt()).isEqualTo(Instant.parse("2026-10-05T14:00:00Z"));
        assertThat(occurrence.scheduledEndAt()).isEqualTo(Instant.parse("2026-10-05T23:00:00Z")); // 04:30 on D+1
        assertThat(WorkdayShift.of(occurrence, INDIA)).extracting(WorkdayShift::startTime, WorkdayShift::endTime, WorkdayShift::scheduledSeconds)
                .containsExactly("19:30", "04:30", 9L * 3600);
        assertThat(US.crossesMidnight()).isTrue();
        assertThat(US.scheduledMinutes()).isEqualTo(9 * 60);
    }

    @Test
    void aStartFiveMinutesEarlyAndAStartAfterMidnightBelongToTheSameShift() {
        EmployeeSchedule schedule = assigned(US);
        ShiftAttribution early = at("2026-10-05T13:55:00Z", INDIA, schedule);              // 19:25 on D
        ShiftAttribution afterMidnight = at("2026-10-05T21:30:00Z", INDIA, schedule);      // 03:00 on D+1
        ShiftAttribution lateButInGrace = at("2026-10-06T01:30:00Z", INDIA, schedule);     // 07:00 on D+1 (grace until 07:30)
        for (ShiftAttribution attribution : List.of(early, afterMidnight, lateButInGrace)) {
            assertThat(attribution.occurrence().date()).isEqualTo(D);
            assertThat(attribution.isOutsideShift()).isFalse();
        }
    }

    @Test
    void aUkShiftBelongsToItsOwnDate() {
        EmployeeSchedule schedule = assigned(UK);
        assertThat(at("2026-10-05T07:30:00Z", INDIA, schedule).occurrence().date()).isEqualTo(D);                       // 13:00, in the early grace
        assertThat(at("2026-10-05T19:00:00Z", INDIA, schedule).occurrence().date()).isEqualTo(D);                       // 00:30 on D+1, in the late grace
        assertThat(at("2026-10-05T19:00:00Z", INDIA, schedule).occurrence().scheduledEndAt()).isEqualTo(Instant.parse("2026-10-05T17:30:00Z"));
        assertThat(WorkdayShift.of(at("2026-10-05T07:30:00Z", INDIA, schedule).occurrence(), INDIA).scheduledSeconds()).isEqualTo(9 * 3600);
    }

    @Test
    void aStartInTheGapGoesToTheNearestOccurrenceAndIsFlagged() {
        EmployeeSchedule schedule = assigned(US);
        ShiftAttribution gap = at("2026-10-05T05:30:00Z", INDIA, schedule); // 11:00 on D: yesterday's shift ended 04:30 (6.5 h), today's starts 19:30 (8.5 h)
        assertThat(gap.occurrence().date()).isEqualTo(D.minusDays(1));
        assertThat(gap.isOutsideShift()).isTrue();
        assertThat(gap.isOffDay()).isFalse();
        ShiftAttribution nearerTheNextOne = at("2026-10-05T09:00:00Z", INDIA, schedule); // 14:30 on D: 10 h after the end, 5 h before the start
        assertThat(nearerTheNextOne.occurrence().date()).isEqualTo(D);
        assertThat(nearerTheNextOne.isOutsideShift()).isTrue();
    }

    @Test
    void consecutiveShiftsNeverShareAWorkday() {
        EmployeeSchedule schedule = assigned(US);
        // the first grace window ends at 07:30 on D+1, the next one opens at 16:30 on D+1: no instant is in both
        assertThat(at("2026-10-06T01:59:00Z", INDIA, schedule).occurrence().date()).isEqualTo(D);                       // 07:29
        assertThat(at("2026-10-06T11:00:00Z", INDIA, schedule).occurrence().date()).isEqualTo(D.plusDays(1));           // 16:30
        assertThat(at("2026-10-06T11:00:00Z", INDIA, schedule).isOutsideShift()).isFalse();
        // two shifts whose graces overlap: the nearest start wins
        ShiftSchedule wideNight = new ShiftSchedule(null, "Wide", LocalTime.of(20, 0), LocalTime.of(8, 0), 720, 720);
        EmployeeSchedule wide = assigned(wideNight);
        ShiftAttribution both = at("2026-10-06T04:30:00Z", INDIA, wide); // 10:00 on D+1: in D's late grace (until 20:00) and D+1's early grace (from 08:00)
        assertThat(both.occurrence().date()).isEqualTo(D.plusDays(1)); // 10 h from tomorrow's start, 14 h from today's
    }

    @Test
    void theUsNightShiftAcrossTheSpringForwardIsOneHourShorter() {
        EmployeeSchedule schedule = assigned(new ShiftSchedule(null, "US night", LocalTime.of(22, 0), LocalTime.of(6, 0), 180, 180));
        ShiftAttribution attribution = at("2026-03-08T03:00:00Z", NEW_YORK, schedule); // 22:00 EST on 7 March
        assertThat(attribution.occurrence().date()).isEqualTo(LocalDate.of(2026, 3, 7));
        assertThat(attribution.occurrence().scheduledEndAt()).isEqualTo(Instant.parse("2026-03-08T10:00:00Z")); // 06:00 EDT
        assertThat(WorkdayShift.of(attribution.occurrence(), NEW_YORK).scheduledSeconds()).isEqualTo(7 * 3600);
        // 08:00 EDT on the 8th is after the late grace of the 7th's shift (09:00 EDT is not yet): still the 7th
        assertThat(at("2026-03-08T12:00:00Z", NEW_YORK, schedule).occurrence().date()).isEqualTo(LocalDate.of(2026, 3, 7));
    }

    @Test
    void theUsNightShiftAcrossTheFallBackIsOneHourLonger() {
        EmployeeSchedule schedule = assigned(new ShiftSchedule(null, "US night", LocalTime.of(22, 0), LocalTime.of(6, 0), 180, 180));
        ShiftAttribution attribution = at("2026-11-01T02:00:00Z", NEW_YORK, schedule); // 22:00 EDT on 31 October
        assertThat(attribution.occurrence().date()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(WorkdayShift.of(attribution.occurrence(), NEW_YORK).scheduledSeconds()).isEqualTo(9 * 3600);
    }

    @Test
    void theRosterEntryBeatsTheAssignmentAndTheAssignmentBeatsTheDefault() {
        EmployeeSchedule schedule = new EmployeeSchedule(ShiftSchedule.calendarDay()).assign(D, US).setEntry(D.plusDays(1), UK).setEntry(D.plusDays(2), null);
        assertThat(schedule.dayOn(D.minusDays(1)).source()).isEqualTo(EmployeeSchedule.Source.DEFAULT);
        assertThat(schedule.dayOn(D.minusDays(1)).shift().name()).isEqualTo("General day");
        assertThat(schedule.dayOn(D).source()).isEqualTo(EmployeeSchedule.Source.ASSIGNMENT);
        assertThat(schedule.dayOn(D).shift()).isEqualTo(US);
        assertThat(schedule.dayOn(D.plusDays(1)).source()).isEqualTo(EmployeeSchedule.Source.ENTRY);
        assertThat(schedule.dayOn(D.plusDays(1)).shift()).isEqualTo(UK);
        assertThat(schedule.dayOn(D.plusDays(2)).isOff()).isTrue();
        assertThat(schedule.assignedOn(D.plusDays(2))).as("the assignment under a week off").isEqualTo(US);
        assertThat(schedule.dayOn(D.plusDays(3)).shift()).isEqualTo(US);
    }

    @Test
    void aPerDateOverrideChangesTheOccurrence() {
        EmployeeSchedule schedule = assigned(US).setEntry(D, UK);
        ShiftAttribution attribution = at("2026-10-05T08:00:00Z", INDIA, schedule); // 13:30 on D
        assertThat(attribution.occurrence().shift()).isEqualTo(UK);
        assertThat(attribution.isOutsideShift()).isFalse();
    }

    @Test
    void rotationalWeekOffsOverSeveralWeeksAreOffOnlyOnTheirDates() {
        EmployeeSchedule schedule = assigned(US);
        List<LocalDate> offDays = List.of(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 31));
        offDays.forEach(offDay -> schedule.setEntry(offDay, null));
        long offCount = LocalDate.of(2026, 10, 5).datesUntil(LocalDate.of(2026, 11, 2)).filter(date -> schedule.dayOn(date).isOff()).count();
        assertThat(offCount).isEqualTo(4);
        for (LocalDate offDay : offDays) {
            Instant noon = offDay.atTime(12, 0).atZone(INDIA).toInstant();
            ShiftAttribution attribution = ShiftAttribution.of(noon, INDIA, schedule);
            assertThat(attribution.isOffDay()).as("noon on the off day %s", offDay).isTrue();
            assertThat(attribution.isOutsideShift()).isTrue();
            assertThat(attribution.occurrence().date()).isEqualTo(offDay);
        }
        // the night after an off day belongs to nobody's shift only until the next occurrence opens
        Instant eveningBefore = LocalDate.of(2026, 10, 6).atTime(20, 0).atZone(INDIA).toInstant();
        assertThat(ShiftAttribution.of(eveningBefore, INDIA, schedule).isOffDay()).isFalse();
    }

    @Test
    void workInsideTheNightShiftOfTheDayBeforeAnOffDayIsNotOffDayWork() {
        EmployeeSchedule schedule = assigned(US).setEntry(D.plusDays(1), null);
        ShiftAttribution afterMidnightOnTheOffDate = at("2026-10-05T21:00:00Z", INDIA, schedule); // 02:30 on D+1, the last hours of D's shift
        assertThat(afterMidnightOnTheOffDate.occurrence().date()).isEqualTo(D);
        assertThat(afterMidnightOnTheOffDate.isOffDay()).isFalse();
    }

    @Test
    void extraTimeIsTheProductiveTimeOutsideTheScheduledWindow() {
        ShiftOccurrence occurrence = US.occurrenceOn(D, INDIA); // 19:30 D to 04:30 D+1 (14:00Z to 23:00Z)
        WorkdayShift shift = WorkdayShift.of(occurrence, INDIA);
        Instant now = Instant.parse("2026-10-06T06:00:00Z");
        List<TimeInterval> worked = List.of(
                new TimeInterval(Instant.parse("2026-10-05T13:50:00Z"), Instant.parse("2026-10-05T18:00:00Z")),  // 10 minutes before the start
                new TimeInterval(Instant.parse("2026-10-05T18:30:00Z"), Instant.parse("2026-10-05T23:20:00Z"))); // 20 minutes after the end
        assertThat(WorkdayTimeline.extraSeconds(shift, false, worked, now)).isEqualTo(30 * 60);
        assertThat(WorkdayTimeline.extraSeconds(shift, true, worked, now)).as("a week off: all of it").isEqualTo(IntervalMath.sumSeconds(worked, now));
        assertThat(WorkdayTimeline.extraSeconds(WorkdayShift.of(ShiftSchedule.calendarDay().occurrenceOn(D, INDIA), INDIA), false, worked, now))
                .as("the calendar-day shift has no extra time").isZero();
        assertThat(WorkdayTimeline.extraSeconds(null, false, worked, now)).isZero();
        List<TimeInterval> running = List.of(new TimeInterval(Instant.parse("2026-10-05T22:30:00Z"), null));
        assertThat(WorkdayTimeline.extraSeconds(shift, false, running, now)).as("a running interval counts until now: 23:00Z to 06:00Z").isEqualTo(7 * 60 * 60);
    }
}
