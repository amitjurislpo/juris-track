package com.jurislpo.juristrack.engine;

/**
 * The totals of one workday, in whole seconds: productive (work) time, break time, the span from the start of the day to its end
 * (or to now while it is open) and the number of breaks taken.
 */
public record WorkdayTotals(long productiveSeconds, long breakSeconds, long spanSeconds, int breakCount) {
}
