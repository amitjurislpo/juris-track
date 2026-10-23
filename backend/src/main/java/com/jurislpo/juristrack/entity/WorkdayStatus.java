package com.jurislpo.juristrack.entity;

/** The state of a workday. Stored as the PostgreSQL enum {@code workday_status}. */
public enum WorkdayStatus {
    WORKING,
    ON_BREAK,
    COMPLETED
}
