package com.jurislpo.juristrack.service;

/** The action names written to {@code audit_logs.action} (stored as {@link #storedName()}; never rename a stored name). */
public enum AuditAction {
    /** A sign-in code was emailed to an active account. */
    SIGN_IN_CODE_REQUESTED("auth.sign_in_code_requested"),
    /** Signed in with the emailed code (a session was created). */
    SIGNED_IN("auth.signed_in"),
    /** A sign-in with a wrong, expired or burnt code, or for an unknown or inactive account. */
    SIGN_IN_FAILED("auth.sign_in_failed"),
    /** Signed out (the session was ended). */
    SIGNED_OUT("auth.signed_out"),
    /** A refresh token was used twice: the whole session was ended. */
    SESSION_REVOKED_FOR_REFRESH_REUSE("auth.session_revoked_refresh_reuse"),
    /** Embedded in JurisOne: a business was created from a JurisOne token. */
    BUSINESS_PROVISIONED("integration.business_provisioned"),
    /** Embedded in JurisOne: an existing standalone business was linked to its JurisOne business. */
    BUSINESS_LINKED("integration.business_linked"),
    /** Embedded in JurisOne: a user was created from a JurisOne token. */
    USER_PROVISIONED("integration.user_provisioned"),
    /** Embedded in JurisOne: an existing user (same email) was linked to its JurisOne employee. */
    USER_LINKED("integration.user_linked"),
    /** Embedded in JurisOne: name, role or manager changed because the token said so. */
    USER_UPDATED_FROM_TOKEN("integration.user_updated"),
    /** The super admin created a business (together with its owner, see {@link #EMPLOYEE_CREATED}). */
    BUSINESS_CREATED("business.created"),
    /** Business settings changed (name, time zone, stale workday hours, display time zones), by the super admin or the owner. */
    BUSINESS_UPDATED("business.updated"),
    /** The super admin suspended a business. */
    BUSINESS_SUSPENDED("business.suspended"),
    /** The super admin lifted a business's suspension. */
    BUSINESS_REACTIVATED("business.reactivated"),
    /** A user was added to a business (an employee by the owner, or the owner by the super admin). */
    EMPLOYEE_CREATED("employee.created"),
    /** An employee's details, role or manager changed. */
    EMPLOYEE_UPDATED("employee.updated"),
    /** An employee was deactivated (their sessions were ended). */
    EMPLOYEE_DEACTIVATED("employee.deactivated"),
    /** A deactivated employee was activated again. */
    EMPLOYEE_REACTIVATED("employee.reactivated"),
    /** The employee started their workday (a new workday with its first work session). */
    WORKDAY_STARTED("time.workday_started"),
    /** The employee started work again after ending the day: the same workday was reopened with a new work session. */
    WORKDAY_REOPENED("time.workday_reopened"),
    /** The employee started a break. */
    BREAK_STARTED("time.break_started"),
    /** The employee ended a break and is working again. */
    WORK_RESUMED("time.work_resumed"),
    /** The employee ended their workday. */
    WORKDAY_ENDED("time.workday_ended"),
    /** Somebody else closed an employee's open workday (so far only when the employee is deactivated); the reason says why. */
    WORKDAY_CLOSED("workday.closed"),
    /** A manager or the owner read a report on other people's workdays (one entry per request; filters in {@code after}). */
    REPORT_VIEWED("reports.viewed"),
    /** A manager or the owner downloaded the workday report as CSV (filters, rows and display time zone in {@code after}). */
    REPORT_EXPORTED("reports.exported");

    private final String storedName;

    AuditAction(String storedName) {
        this.storedName = storedName;
    }

    public String storedName() {
        return storedName;
    }
}
