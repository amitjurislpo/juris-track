package com.jurislpo.juristrack.engine;

/**
 * Where an employee is in their workday, as the API shows it. {@code DAY_ENDED} is a workday with status {@code COMPLETED};
 * {@code NOT_STARTED} means there is no workday yet (none open, none for today).
 */
public enum TrackingStatus {
    NOT_STARTED,
    WORKING,
    ON_BREAK,
    DAY_ENDED
}
