package com.jurislpo.juristrack.engine;

/** What {@link TimeStateMachine#decide} answers for one event in one state: the change to make, nothing (a repeat), or a refusal. */
public enum TimeTransition {
    /** No workday today: create it with an open work session. */
    START_WORKDAY,
    /** Today's workday was ended: reopen it with a new work session (the gap counts as neither productive nor break time). */
    REOPEN_WORKDAY,
    /** Close the open work session and open a break. */
    START_BREAK,
    /** Close the open break and open a new work session. */
    RESUME_WORK,
    /** Close the open work session and complete the workday. */
    END_WORKDAY,
    /** The employee is already where the event would take them: change nothing and answer the current state. */
    ALREADY_APPLIED,
    /** A break or the end of the day before the workday started (or after it ended, for a break). */
    REJECTED_WORKDAY_NOT_STARTED,
    /** Resume without a break to end. */
    REJECTED_NOT_ON_BREAK,
    /** End the day while on a break: the break must be ended (resume) first, so its end time is real. */
    REJECTED_BREAK_IN_PROGRESS
}
