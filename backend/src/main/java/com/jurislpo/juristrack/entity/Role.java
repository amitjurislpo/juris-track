package com.jurislpo.juristrack.entity;

/**
 * What a user may do besides tracking their own time (which everybody does with the same function). Stored as the
 * PostgreSQL enum {@code user_role}.
 */
public enum Role {
    /** The platform user: belongs to no business; creates businesses and their owner (ADMIN). Standalone only. */
    SUPER_ADMIN,
    /** The business owner: adds employees and assigns their managers, sees everyone, corrects time, reports, audit log, settings. */
    ADMIN,
    /** A reporting manager: sees the work, break and productive time of their reports (users whose manager they are). */
    MANAGER,
    /** Every employee (embedded in JurisOne: every JurisOne employee, whatever their JurisOne role). */
    EMPLOYEE
}
