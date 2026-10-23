package com.jurislpo.juristrack.engine;

/**
 * The time-tracking state machine, ported from the old app's {@code time-tracking.service.ts} (commit 679d9e5):
 * <pre>
 *   NOT_STARTED --START--&gt; WORKING --BREAK--&gt; ON_BREAK --RESUME--&gt; WORKING
 *                          WORKING --END----&gt; DAY_ENDED --START--&gt; WORKING (the same workday, reopened)
 * </pre>
 * An event that would take the employee where they already are is a repeat ({@link TimeTransition#ALREADY_APPLIED}: a
 * double-click changes nothing). Exactly as in the old code, START while on a break is such a repeat too (the day is already
 * running), and END after the day ended is one as well. Ending the day while on a break is refused: the break must be ended first
 * so that its end time is real. Spring-free; the service applies the answer while it holds the employee's lock.
 */
public final class TimeStateMachine {

    private TimeStateMachine() {
    }

    /**
     * The transition for {@code event} when the employee is in {@code status}. {@code status} is the open workday's status when one
     * is open (from any date: a night shift may run past midnight), otherwise {@code DAY_ENDED} when today's workday was completed,
     * otherwise {@code NOT_STARTED}.
     */
    public static TimeTransition decide(TrackingStatus status, TimeEventType event) {
        return switch (event) {
            case START -> switch (status) {
                case NOT_STARTED -> TimeTransition.START_WORKDAY;
                case DAY_ENDED -> TimeTransition.REOPEN_WORKDAY;
                case WORKING, ON_BREAK -> TimeTransition.ALREADY_APPLIED;
            };
            case BREAK -> switch (status) {
                case WORKING -> TimeTransition.START_BREAK;
                case ON_BREAK -> TimeTransition.ALREADY_APPLIED;
                case NOT_STARTED, DAY_ENDED -> TimeTransition.REJECTED_WORKDAY_NOT_STARTED;
            };
            case RESUME -> switch (status) {
                case ON_BREAK -> TimeTransition.RESUME_WORK;
                case WORKING -> TimeTransition.ALREADY_APPLIED;
                case NOT_STARTED, DAY_ENDED -> TimeTransition.REJECTED_NOT_ON_BREAK;
            };
            case END -> switch (status) {
                case WORKING -> TimeTransition.END_WORKDAY;
                case DAY_ENDED -> TimeTransition.ALREADY_APPLIED;
                case ON_BREAK -> TimeTransition.REJECTED_BREAK_IN_PROGRESS;
                case NOT_STARTED -> TimeTransition.REJECTED_WORKDAY_NOT_STARTED;
            };
        };
    }
}
