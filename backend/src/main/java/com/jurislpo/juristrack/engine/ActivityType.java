package com.jurislpo.juristrack.engine;

/** The last thing that happened in a workday (the labels "Started work", ... are display text and stay in the frontend). */
public enum ActivityType {
    STARTED_WORK,
    STARTED_BREAK,
    RESUMED_WORK,
    ENDED_DAY
}
