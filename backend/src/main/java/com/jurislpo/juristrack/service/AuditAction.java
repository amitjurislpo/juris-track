package com.jurislpo.juristrack.service;

/**
 * The action names written to {@code audit_logs.action} (stored as {@link #storedName()}; never rename a stored name), each with its
 * {@link AuditCategory} and the outcome it has unless the entry says otherwise. The catalogue (when each is written) is in
 * references/API.md, section "Audit log".
 */
public enum AuditAction {
    // ── sign-in and sessions ──
    /** A sign-in code was asked for: emailed to an active account (SUCCESS), or refused without telling the caller (DENIED: unknown or inactive account, suspended business). */
    SIGN_IN_CODE_REQUESTED("auth.sign_in_code_requested", AuditCategory.AUTH, AuditOutcome.SUCCESS),
    /** Signed in with the emailed code (a session was created). */
    SIGNED_IN("auth.signed_in", AuditCategory.AUTH, AuditOutcome.SUCCESS),
    /** A sign-in was refused: wrong, expired, used or burnt code, unknown or inactive account, suspended business ({@code after.reason}). */
    SIGN_IN_FAILED("auth.sign_in_failed", AuditCategory.AUTH, AuditOutcome.DENIED),
    /** The wrong try that used up a code's attempts: the code no longer works. */
    SIGN_IN_CODE_BURNT("auth.sign_in_code_burnt", AuditCategory.AUTH, AuditOutcome.DENIED),
    /** A sign-in rate limit was hit (at most one entry per limit, key and window). */
    RATE_LIMITED("auth.rate_limited", AuditCategory.AUTH, AuditOutcome.DENIED),
    /** Signed out (the session was ended). */
    SIGNED_OUT("auth.signed_out", AuditCategory.AUTH, AuditOutcome.SUCCESS),
    /** A refresh token was used twice: the whole session was ended. */
    SESSION_REVOKED_FOR_REFRESH_REUSE("auth.session_revoked_refresh_reuse", AuditCategory.AUTH, AuditOutcome.DENIED),
    /** A session refresh was refused: unknown, revoked or expired session, deactivated user (at most one entry per reason, IP and window). */
    SESSION_REFRESH_FAILED("auth.session_refresh_failed", AuditCategory.AUTH, AuditOutcome.DENIED),

    // ── security refusals ──
    /** 403: a signed-in user called something their role or scope does not allow. */
    ACCESS_DENIED("security.access_denied", AuditCategory.SECURITY, AuditOutcome.DENIED),
    /** 403 BUSINESS_SUSPENDED: a user of a suspended business called the API (at most one entry per user and window). */
    SUSPENDED_BUSINESS_REQUEST("security.suspended_business_request", AuditCategory.SECURITY, AuditOutcome.DENIED),
    /** A forged or malformed session cookie, or one of an ended session, was presented (at most one entry per reason, IP and window). */
    INVALID_SESSION_COOKIE("security.invalid_session_cookie", AuditCategory.SECURITY, AuditOutcome.DENIED),
    /** 403 CROSS_SITE_BLOCKED: a cookie write without the web app's Origin (at most one entry per IP and window). */
    CROSS_SITE_REQUEST_BLOCKED("security.cross_site_request_blocked", AuditCategory.SECURITY, AuditOutcome.DENIED),

    // ── access to other people's data ──
    /** The owner listed the business's people (filters in {@code after}). */
    EMPLOYEE_LIST_VIEWED("access.employee_list_viewed", AuditCategory.ACCESS, AuditOutcome.SUCCESS),
    /** The owner opened another person's record. */
    EMPLOYEE_VIEWED("access.employee_viewed", AuditCategory.ACCESS, AuditOutcome.SUCCESS),
    /** The super admin listed the businesses (with their owners). */
    BUSINESS_LIST_VIEWED("access.business_list_viewed", AuditCategory.ACCESS, AuditOutcome.SUCCESS),
    /** The super admin opened a business (with its owner). */
    BUSINESS_VIEWED("access.business_viewed", AuditCategory.ACCESS, AuditOutcome.SUCCESS),

    // ── the audit log ──
    /** The audit log was exported as CSV (filters and row count in {@code after}). */
    AUDIT_LOG_EXPORTED("audit.exported", AuditCategory.AUDIT, AuditOutcome.SUCCESS),

    // ── embedded in JurisOne ──
    /** A business was created from a JurisOne token. */
    BUSINESS_PROVISIONED("integration.business_provisioned", AuditCategory.INTEGRATION, AuditOutcome.SUCCESS),
    /** An existing standalone business was linked to its JurisOne business. */
    BUSINESS_LINKED("integration.business_linked", AuditCategory.INTEGRATION, AuditOutcome.SUCCESS),
    /** A linked business was renamed because the token carried JurisOne's new name. */
    BUSINESS_UPDATED_FROM_TOKEN("integration.business_updated", AuditCategory.INTEGRATION, AuditOutcome.SUCCESS),
    /** A user was created from a JurisOne token. */
    USER_PROVISIONED("integration.user_provisioned", AuditCategory.INTEGRATION, AuditOutcome.SUCCESS),
    /** An existing user (same email) was linked to its JurisOne employee. */
    USER_LINKED("integration.user_linked", AuditCategory.INTEGRATION, AuditOutcome.SUCCESS),
    /** Name, email, role or manager changed because the token said so. */
    USER_UPDATED_FROM_TOKEN("integration.user_updated", AuditCategory.INTEGRATION, AuditOutcome.SUCCESS),
    /** A JurisOne token was refused (reason code only, never the token; at most one entry per reason, IP and window). */
    INTEGRATION_TOKEN_REJECTED("integration.token_rejected", AuditCategory.INTEGRATION, AuditOutcome.DENIED),
    /** A valid JurisOne token whose person may not use JurisTrack: deactivated, or the email belongs to another business or the super admin. */
    INTEGRATION_USER_REFUSED("integration.user_refused", AuditCategory.INTEGRATION, AuditOutcome.DENIED),

    // ── businesses ──
    /** The super admin created a business (together with its owner, see {@link #EMPLOYEE_CREATED}). */
    BUSINESS_CREATED("business.created", AuditCategory.BUSINESS, AuditOutcome.SUCCESS),
    /** Business settings changed (name, time zone, stale workday hours, display time zones), by the super admin or the owner. */
    BUSINESS_UPDATED("business.updated", AuditCategory.BUSINESS, AuditOutcome.SUCCESS),
    /** The super admin suspended a business. */
    BUSINESS_SUSPENDED("business.suspended", AuditCategory.BUSINESS, AuditOutcome.SUCCESS),
    /** The super admin lifted a business's suspension. */
    BUSINESS_REACTIVATED("business.reactivated", AuditCategory.BUSINESS, AuditOutcome.SUCCESS),

    // ── people ──
    /** A user was added to a business (an employee by the owner, or the owner by the super admin). */
    EMPLOYEE_CREATED("employee.created", AuditCategory.PEOPLE, AuditOutcome.SUCCESS),
    /** An employee's details, role or manager changed. */
    EMPLOYEE_UPDATED("employee.updated", AuditCategory.PEOPLE, AuditOutcome.SUCCESS),
    /** An employee was deactivated (their sessions were ended). */
    EMPLOYEE_DEACTIVATED("employee.deactivated", AuditCategory.PEOPLE, AuditOutcome.SUCCESS),
    /** A deactivated employee was activated again. */
    EMPLOYEE_REACTIVATED("employee.reactivated", AuditCategory.PEOPLE, AuditOutcome.SUCCESS),
    /** A business rule refused a change to an employee (422: manager rules, role, last owner, own account; the code is the reason). */
    EMPLOYEE_CHANGE_REFUSED("employee.change_refused", AuditCategory.PEOPLE, AuditOutcome.DENIED),

    // ── time tracking ──
    /** The employee started their workday (a new workday with its first work session). */
    WORKDAY_STARTED("time.workday_started", AuditCategory.TIME, AuditOutcome.SUCCESS),
    /** The employee started work again after ending the day: the same workday was reopened with a new work session. */
    WORKDAY_REOPENED("time.workday_reopened", AuditCategory.TIME, AuditOutcome.SUCCESS),
    /** The employee started a break. */
    BREAK_STARTED("time.break_started", AuditCategory.TIME, AuditOutcome.SUCCESS),
    /** The employee ended a break and is working again. */
    WORK_RESUMED("time.work_resumed", AuditCategory.TIME, AuditOutcome.SUCCESS),
    /** The employee ended their workday. */
    WORKDAY_ENDED("time.workday_ended", AuditCategory.TIME, AuditOutcome.SUCCESS),
    /** A time event could not be applied because the open workday's intervals do not match its status (500 TIME_RECORD_INCONSISTENT). */
    TIME_EVENT_FAILED("time.event_failed", AuditCategory.TIME, AuditOutcome.FAILED),
    /** Somebody else closed an employee's open workday (so far only when the employee is deactivated); the reason says why. */
    WORKDAY_CLOSED("workday.closed", AuditCategory.TIME, AuditOutcome.SUCCESS),
    /** A manager or the owner corrected a work or break interval of somebody in their scope (reason mandatory). */
    TIME_SESSION_ADJUSTED("time.session_adjusted", AuditCategory.TIME, AuditOutcome.SUCCESS),
    /** A manager or the owner ended somebody's open (forgotten) workday (reason mandatory). */
    TIME_WORKDAY_CLOSED("time.workday_closed", AuditCategory.TIME, AuditOutcome.SUCCESS),
    /** Access: a manager or the owner opened one person's live state on the Team tab. */
    TEAM_MEMBER_VIEWED("access.team_member_viewed", AuditCategory.ACCESS, AuditOutcome.SUCCESS),
    /** Access: a manager or the owner opened one person's history (the date range is recorded). */
    TEAM_MEMBER_WORKDAYS_VIEWED("access.team_member_workdays_viewed", AuditCategory.ACCESS, AuditOutcome.SUCCESS),
    /** Access: a manager or the owner opened one workday of somebody else. */
    WORKDAY_VIEWED("access.workday_viewed", AuditCategory.ACCESS, AuditOutcome.SUCCESS);

    private final String storedName;
    private final AuditCategory category;
    private final AuditOutcome defaultOutcome;

    AuditAction(String storedName, AuditCategory category, AuditOutcome defaultOutcome) {
        this.storedName = storedName;
        this.category = category;
        this.defaultOutcome = defaultOutcome;
    }

    public String storedName() {
        return storedName;
    }

    public AuditCategory category() {
        return category;
    }

    public AuditOutcome defaultOutcome() {
        return defaultOutcome;
    }
}
