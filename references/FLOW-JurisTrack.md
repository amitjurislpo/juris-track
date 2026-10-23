# JurisTrack: flow diagrams

| Version | Date | What changed | Requested by |
|---|---|---|---|
| 1.0 | 2026-10-23 | Initial version | Owner |
| 1.1 | 2026-10-24 | Section 3 and 7: the snapshot and the board row carry `lastBreak`, shown as the time of the last break (FRD 1.1: FR-TIME-11, FR-TEAM-12); history and report rows and the CSV carry it too and label the day's start "Work started" and show an end after midnight with its date (FR-HIST-6, FR-HIST-7, FR-REP-14). | Owner |
| 1.2 | 2026-10-24 | Login access: the owner can switch an employee's sign-in off and on (`PUT /api/employees/{employeeId}/login-access`), separate from Deactivate; sign-in flow and a new employee login access flow (section 15) | Owner |
| 2.0 | 2026-10-24 | Shift-based calculation (new section 14: shift occurrence, attribution, the START decision, roster); sections 3, 4 and 5 rewritten from the calendar day to the shift occurrence | Owner |

This is the document version, not the app version. Companion documents (same folder): [FRD-JurisTrack.md](FRD-JurisTrack.md) (the requirements, ids `FR-...` are quoted below) and [FLOW-JurisTrack-JurisOne-Connection.md](FLOW-JurisTrack-JurisOne-Connection.md) (how JurisTrack and JurisOne talk to each other).

How to read: boxes are steps, diamonds are decisions, the label on an arrow is the answer or the error code. Roles: **Super admin**, **Owner** (role ADMIN), **Manager**, **Employee**. Every diagram has a one-sentence caption.

Contents: 1 System overview, 2 Sign-in and session, 3 Start work, break, resume and end day, 4 Reopening after End Day, 5 Forgotten days: no auto-close and the Not ended flag, 6 Time correction, 7 Team scope, 8 Reports and CSV, 9 Audit write and read, 10 Business creation and owner, 11 Business suspension and reactivation, 12 Employee deactivation, 13 Embedded in JurisOne, 14 Shifts and the roster (occurrence, attribution, the START decision), 15 Employee login access.

---

## 1. System overview

Who talks to whom: the browser only talks to its own origin, the API does all the work and the checking, PostgreSQL keeps the data and isolates businesses, and email carries the sign-in code.

```mermaid
flowchart LR
    subgraph Users["People"]
        SA["Super admin"]
        OW["Owner"]
        MG["Manager"]
        EM["Employee"]
    end
    subgraph Web["Web app, standalone"]
        SPA["React single page app<br/>pages, timer, language, display time zone"]
        PROXY["Web server or dev proxy<br/>sends /api to the API"]
    end
    subgraph Api["Spring Boot API"]
        FILT["Filters: request id, body limit,<br/>origin check, cookie or Bearer token"]
        CTRL["Controllers check the role"]
        SVC["Services: scope, time engine,<br/>corrections, reports, audit"]
    end
    DB[("PostgreSQL<br/>business_id and row level security<br/>append only audit_logs")]
    MAIL["Email server SMTP<br/>sign-in codes"]
    subgraph J1["JurisOne"]
        JB["JurisOne browser and backend gateway<br/>signs one token per request"]
    end
    SA --> SPA
    OW --> SPA
    MG --> SPA
    EM --> SPA
    SPA -->|"same origin /api"| PROXY
    PROXY -->|"cookies jt_at and jt_rt"| FILT
    JB -->|"Bearer token, internal network only"| FILT
    FILT --> CTRL
    CTRL --> SVC
    SVC -->|"one connection per request, tenant set"| DB
    SVC -->|"sign-in code"| MAIL
    MAIL -.->|"code arrives in the inbox"| EM
```

Caption: one API and one database serve every business in both modes; the only difference between standalone and embedded is how the caller is proven.

There is no scheduled job: nothing happens without a request. "Not ended" is computed when read (see section 5). Rate-limit and audit-aggregation windows live in memory of the API instance.

---

## 2. Sign-in by emailed code, session refresh and rate limits (FR-AUTH-1 to FR-AUTH-14)

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant W as Web app
    participant A as API
    participant M as Email server
    participant D as Database

    U->>W: Types email
    W->>A: POST /api/auth/otp-requests
    A->>A: Check rate limit per IP 10 and per email 5 in 15 minutes
    alt limit hit
        A->>D: Audit auth.rate_limited once per window
        A-->>W: 429 RATE_LIMITED
    else allowed
        A->>D: Find user, must be active, login not switched off and business not suspended
        alt known active user
            A->>D: Store code hash, valid 10 minutes
            A->>M: Send 6 digit code
            A->>D: Audit auth.sign_in_code_requested SUCCESS
        else unknown, inactive, login disabled or suspended
            A->>D: Audit auth.sign_in_code_requested DENIED with reason, once per window
        end
        A-->>W: 200 same text in every case
    end
    U->>W: Types the 6 digit code, may tick keep me signed in
    W->>A: POST /api/auth/sessions
    A->>A: Failed sign in limit per IP 20 and per account 10
    alt too many failures
        A->>D: Audit auth.rate_limited
        A-->>W: 429 RATE_LIMITED
    else code wrong, expired, used or burnt, or account unknown, inactive, login disabled, suspended
        A->>D: Audit auth.sign_in_failed with reason
        Note over A,D: 5th wrong try also writes auth.sign_in_code_burnt
        A-->>W: 401 same message for every cause
    else code correct
        A->>D: Mark code used, create session, audit auth.signed_in
        A-->>W: 201 cookies jt_at 5 minutes and jt_rt refresh
        W->>W: Go to the home of the role
    end
    Note over W,A: Later, every call carries jt_at
    W->>A: Any call with expired jt_at
    A-->>W: 401 TOKEN_EXPIRED
    W->>A: POST /api/auth/session-refreshes once
    alt refresh token valid and unused
        A-->>W: 201 new jt_at and new jt_rt, nothing audited
        W->>A: Repeat the original call once
    else used again within 30 seconds grace
        A-->>W: 201 new jt_at only
    else used again later, session ended, expired, user deactivated or login disabled
        A->>D: Audit auth.session_revoked_refresh_reuse or auth.session_refresh_failed
        A-->>W: 401 and cookies cleared
        W->>U: Back to the sign-in page
    end
    U->>W: Sign out
    W->>A: DELETE /api/auth/sessions/current
    A->>D: End session, audit auth.signed_out
    A-->>W: 200
```

Caption: the answer to a code request never reveals whether the email exists, wrong tries and request counts are limited, and the short access cookie is renewed silently by a rotating refresh token.

The client address used by the limits is the connection's, or the one in `X-Forwarded-For` only when the request comes from a trusted proxy (FR-AUTH-14).

---

## 3. Start work, break, resume and end day (FR-TIME-1 to FR-TIME-10)

### 3.1 The states

```mermaid
stateDiagram-v2
    [*] --> NotStarted
    NotStarted --> Working: START, new workday and first work session
    Working --> OnBreak: BREAK, work session ends and break session starts
    OnBreak --> Working: RESUME, break ends and new work session starts
    Working --> DayEnded: END, work session ends and totals are stored
    DayEnded --> Working: START in the same shift occurrence, workday reopened with a new work session
    OnBreak --> DayEnded: closed by manager, owner, deactivation or suspension
    Working --> DayEnded: closed by manager, owner, deactivation or suspension
    DayEnded --> NotStarted: the next shift occurrence is nearer than the one that ended (see section 14)
    note right of Working
        Not ended is a flag, not a state.
        The day is open for longer than the stale hours.
    end note
    note right of OnBreak
        END is refused here with 409 BREAK_IN_PROGRESS.
    end note
```

Caption: an employee is always in exactly one of four states, and only these moves are allowed; repeating the move you already made changes nothing.

### 3.2 What happens on one button press

```mermaid
flowchart TD
    A["Employee presses a button: START, BREAK, RESUME or END"] --> B["Web app disables the button and sends POST /api/me/time-events"]
    B --> C{"Signed in and role is not super admin?"}
    C -->|"no"| E1["401 or 403 FORBIDDEN"]
    C -->|"yes"| C2{"Business suspended?"}
    C2 -->|"yes"| E2["403 BUSINESS_SUSPENDED"]
    C2 -->|"no"| D{"Type is START, BREAK, RESUME or END?"}
    D -->|"no"| E3["400 VALIDATION_FAILED on field type"]
    D -->|"yes"| F["Lock the employee row, so one request at a time"]
    F --> G{"Account still active?"}
    G -->|"no"| E4["403 This account is deactivated"]
    G -->|"yes"| H["Find the shift occurrence of now (section 14), then read the state: open workday of any date, else the ended workday of that occurrence, else not started"]
    H --> I{"Does the event fit the state?"}
    I -->|"already there, for example double click"| R["200 alreadyApplied true<br/>nothing created, nothing audited"]
    I -->|"START in an occurrence that has no workday yet"| NEWDAY["New workday dated with the shift date, shift snapshot, outside-shift and off-day flags"]
    NEWDAY --> J
    I -->|"BREAK before start or after end"| E5["409 WORKDAY_NOT_STARTED"]
    I -->|"END before start"| E5
    I -->|"RESUME without a break"| E6["409 NOT_ON_BREAK"]
    I -->|"END while on a break"| E7["409 BREAK_IN_PROGRESS"]
    I -->|"valid move"| J["Stamp now to the millisecond, never before the latest recorded instant"]
    J --> K{"Open intervals match the status?"}
    K -->|"no"| E8["500 TIME_RECORD_INCONSISTENT<br/>audit time.event_failed FAILED"]
    K -->|"yes"| L["Change workday and work or break session"]
    L --> M["Recompute stored totals on END or reopen"]
    M --> N["Audit time.workday_started, workday_reopened, break_started, work_resumed or workday_ended in the same transaction"]
    N --> O["201 with message and the new state"]
    O --> P["Web app refreshes the timer from the server clock"]
    R --> P
```

Caption: every press is checked against the stored state under a lock, a repeat is harmless, an impossible move gets a clear 409, and only a real change is audited.

The answer after a press (and the team board row) also carries `lastBreak`, the break that started last (FR-TIME-11, FR-TEAM-12); the employee and the manager see its start time next to the break count.

---

## 4. Reopening after End Day in the same shift occurrence (FR-TIME business rule "Reopening")

```mermaid
flowchart TD
    A["Employee ended the day at 12:00<br/>stored totals: productive and break"] --> B["Later, still inside the same shift occurrence, the employee presses Start again"]
    B --> C{"Is there an open workday?"}
    C -->|"yes"| R["Repeat, 200 alreadyApplied"]
    C -->|"no"| D{"Does the shift occurrence of now already have a workday? a US shift ended at 00:10 and started again at 00:25 is still the same occurrence"}
    D -->|"yes"| E["Reopen that same workday: status WORKING, end cleared, new work session starts now"]
    D -->|"no"| F["Start a new workday for that shift occurrence"]
    E --> G["The gap from 12:00 to now is neither productive nor break time, it stays inside the span"]
    G --> H["Stored totals are emptied while the day is open"]
    H --> I["Audit time.workday_reopened"]
    I --> J["Employee ends the day again: totals recomputed from all intervals, audit time.workday_ended"]
    D -.->|"the day belongs to an earlier shift occurrence"| F
```

Caption: starting again in the same shift occurrence continues the same workday, and the time between the two stretches counts as neither work nor break; the next shift is a new workday.

---

## 5. Forgotten days: no auto-close, the Not ended flag and who can close a day

```mermaid
flowchart TD
    A["A workday is open: WORKING or ON_BREAK"] --> B["Anybody reads the state or a board or a report"]
    B --> C{"Last uninterrupted stretch longer than the business stale hours? default 14"}
    C -->|"no"| D["Shown normally"]
    C -->|"yes"| E["Flag isStale true, shown as Not ended<br/>computed when read, nothing is changed in the database"]
    E --> F["Board tile Not ended, report status NOT_ENDED, warning on My time"]
    F --> G{"Who ends the day?"}
    G -->|"Employee presses End or Back to work then End"| H["Normal end, time.workday_ended"]
    G -->|"Manager or owner of that person"| I["PUT /api/workdays/:workdayId/status with reason"]
    I --> I2["See section 6, the day ends at the given or last activity time, audit time.workday_closed"]
    G -->|"Owner deactivates the person"| J["Day closed now, workday.closed, see section 12"]
    G -->|"Super admin suspends the business"| K["Day closed now, workday.closed, see section 11"]
    G -->|"nothing happens"| L["The day stays open and keeps the Not ended flag.<br/>There is no scheduled job that closes it."]
```

Caption: the system never closes a forgotten day by itself; it only flags it, and a person with the right scope, a deactivation or a suspension closes it.

---

## 6. Correction of time by a manager or the owner (FR-COR-1 to FR-COR-14)

```mermaid
flowchart TD
    A["Manager or owner opens a person's workday and edits an interval or closes the day"] --> B["PATCH work-sessions or break-sessions, or PUT workday status"]
    B --> C{"Role is ADMIN or MANAGER?"}
    C -->|"no"| X1["403 FORBIDDEN"]
    C -->|"yes"| D{"Body valid? reason 5 to 500 characters, ISO times with Z or offset, not in the future, end after start"}
    D -->|"no"| X2["400 VALIDATION_FAILED, nothing audited"]
    D -->|"yes"| E{"Is the workday the caller's own time?"}
    E -->|"yes"| X3["403 CANNOT_CORRECT_OWN_TIME"]
    E -->|"no"| F{"Workday exists in the caller's business and the person is in scope?"}
    F -->|"no"| X4["404 NOT_FOUND<br/>audit security.out_of_scope_access once per window"]
    F -->|"yes"| G{"Interval exists, belongs to this workday and is of this type?"}
    G -->|"no"| X5["404 Time record not found"]
    G -->|"yes"| H["Lock the employee row, same lock as the employee's own events"]
    H --> I{"Running interval given an end time?"}
    I -->|"yes"| R1["422 INTERVAL_STILL_RUNNING"]
    I -->|"no"| J{"Overlap with another work or break interval of the day?"}
    J -->|"yes"| R2["422 TIME_INTERVAL_OVERLAP"]
    J -->|"no"| K{"First interval of the day is work?"}
    K -->|"no"| R3["422 WORKDAY_MUST_START_WITH_WORK"]
    K -->|"yes"| L{"Inside the window: start of the workday's date to end of the next date, in the zone it started in?"}
    L -->|"no"| R4["422 CORRECTION_OUT_OF_RANGE"]
    L -->|"yes"| M{"Whole workday overlaps another workday of the same person?"}
    M -->|"yes"| R5["422 WORKDAY_OVERLAP"]
    M -->|"no"| N{"Times differ from the stored ones?"}
    N -->|"no"| O["200 Nothing changed, nothing audited"]
    N -->|"yes"| P["Save interval, set workday start and end from its intervals, keep date, recompute stored totals"]
    P --> Q["Audit time.session_adjusted or time.workday_closed in the same transaction, with reason and before and after"]
    Q --> S["200 Time corrected with the new workday"]
    R1 --> T["After rollback audit time.correction_refused DENIED with the code and the operation"]
    R2 --> T
    R3 --> T
    R4 --> T
    R5 --> T
```

Caption: a correction passes role, body, own-record, scope and interval checks in a fixed order, then five business rules; any 422 is rolled back and recorded as a refusal, and a success is recorded with the reason in the same transaction.

For ending a forgotten day (`PUT .../status`) the "interval" steps are replaced by: end must not be before the last recorded activity, must be in the window, and an already ended day answers 200 "This workday had already ended." without an audit entry.

---

## 7. Team scope resolution: who a manager or owner may see (section 2.3 of the FRD)

```mermaid
flowchart TD
    A["Caller asks for the board, a report, a person, a history, a workday or a correction"] --> B{"Caller role"}
    B -->|"Employee or Super admin"| X1["403 FORBIDDEN, audit security.access_denied"]
    B -->|"Owner"| C{"Question is about"}
    B -->|"Manager"| D{"Question is about"}
    C -->|"board or report with scope all, the default"| C1["Everybody of the business, active only on the board"]
    C -->|"board or report with scope direct or indirect"| C2["The owner's own reporting line, like a manager"]
    C -->|"one person, history, workday or correction"| C3{"Is the person the owner?"}
    C3 -->|"yes"| X2["404 for the team pages, the personal section is used instead"]
    C3 -->|"no"| C4{"Person is in the owner's business?"}
    C4 -->|"yes"| OK["Allowed"]
    C4 -->|"no or unknown id"| X3["404 NOT_FOUND"]
    D -->|"board or report with scope direct, the default"| D1["Users whose manager is the caller"]
    D -->|"board or report with scope indirect"| D2["Direct reports plus their reports, one level further"]
    D -->|"scope all"| X4["Board: 400 on scope. Report: 403 FORBIDDEN"]
    D -->|"one person, history, workday or correction"| D3{"Person is a direct or indirect report, read from the reporting line now?"}
    D3 -->|"yes"| OK
    D3 -->|"caller themself, above, beside, other business, unknown"| X3
    X3 --> Y["Audit security.out_of_scope_access once per user and window, no subject stored"]
    OK --> Z["Read allowed: audit access.team_member_viewed, access.team_member_workdays_viewed, access.workday_viewed or reports.viewed"]
```

Caption: the reporting line decides, and anything outside it looks exactly like a record that does not exist.

---

## 8. Reports and CSV export (FR-REP-1 to FR-REP-13)

```mermaid
sequenceDiagram
    autonumber
    actor U as Owner or Manager
    participant W as Web app
    participant A as API
    participant S as Scope service
    participant D as Database

    U->>W: Sets range, scope, person, manager of the day, status, search
    W->>A: GET /api/reports/workdays or /summaries
    A->>A: Role must be ADMIN or MANAGER
    alt Employee or Super admin
        A-->>W: 403 FORBIDDEN
    end
    A->>A: Validate all parameters at once
    alt any parameter bad
        A-->>W: 400 VALIDATION_FAILED with every field
    end
    alt manager asked scope all
        A-->>W: 403 You can see only the people who report to you
    end
    A->>S: Resolve scope direct, indirect or all
    S-->>A: Ids of the people
    alt employeeId or managerId not in scope or unknown
        A->>D: Audit security.out_of_scope_access once per window
        A-->>W: 404 Employee not found or Manager not found
    end
    A->>D: One query for people, one for workdays with employee and manager, one for intervals of open days
    D-->>A: Rows
    A->>A: Totals and daily series over the whole range, open days computed to now
    A->>D: Audit reports.viewed one entry per request, in the same transaction
    A-->>W: 200 rows, totals, dailySeries
    opt Export CSV with Accept text/csv and optional timeZone
        W->>A: Same request for the workdays report
        A->>A: timeZone must be the business zone or a display zone
        alt timeZone not allowed
            A-->>W: 400 on field timeZone
        else more than 50000 rows
            A-->>W: 422 EXPORT_TOO_LARGE, nothing exported, nothing audited
        else within the limit
            A->>A: Build file with all rows, BOM, CRLF, formula guard
            A->>D: Audit reports.exported with filters, row count, zone, file name
            A-->>W: 200 text/csv attachment juristrack-from-to-to.csv
        end
    end
```

Caption: a report is checked and scoped before any data is read, covers the whole date range regardless of paging, and every read and every download writes one audit entry.

A report filtered on the owner's own id reads one's own data and is not audited.

---

## 9. Audit: how an entry is written and how it is read

### 9.1 Writing

```mermaid
flowchart TD
    A["Something happens in a request"] --> B{"What kind of event?"}
    B -->|"A write that succeeds"| C["Entry written in the same transaction as the change.<br/>If the change rolls back, the entry rolls back with it"]
    B -->|"Read of another person's data, a report, an export"| D["Entry written in the read's own transaction.<br/>The data is not served without its entry"]
    B -->|"A refusal or failure: 422 rule, 403, rate limit, bad cookie, bad token, 500 inconsistent record"| E["Entry written in a separate transaction, so it stays although the request fails"]
    B -->|"404 for somebody else's person or workday"| F["security.out_of_scope_access with record type and no subject"]
    B -->|"Own data, successful refresh, 401, 400, 409, viewing the audit log, the live board"| G["Not audited by design"]
    E --> H{"High volume security event?"}
    F --> H
    H -->|"yes"| I{"Already written for this key in the last 15 minutes?"}
    I -->|"yes"| J["Skip, only the first entry per key and window is kept"]
    I -->|"no"| K["Write"]
    H -->|"no"| K
    K --> L{"Anonymous caller over 500 entries from one IP in the window?"}
    L -->|"yes"| M["Not written, the append only log cannot be grown without bound"]
    L -->|"no"| N["Redact secrets and mask unknown emails, then insert with actor, role, subject, action, category, outcome, before, after, reason, source, IP, request id"]
    C --> N
    D --> N
    N --> O[("audit_logs: the database refuses UPDATE, DELETE and TRUNCATE")]
```

Caption: every movement is recorded in the right transaction, noisy security events are written once per 15 minutes, and nothing can change an entry afterwards.

### 9.2 Reading and exporting

```mermaid
sequenceDiagram
    autonumber
    actor U as Owner or Super admin
    participant W as Web app
    participant A as API
    participant D as Database

    U->>W: Opens Audit Log and sets filters
    W->>A: GET /api/audit-logs with filters
    alt Manager or Employee
        A-->>W: 403 FORBIDDEN
    else Owner
        A->>D: Query with row level security, only this business
    else Super admin
        A->>D: Query all entries, optional businessId or none
    end
    A->>A: Bad filter value gives 400 with the parameter name
    D-->>A: Rows
    A-->>W: 200 rows, newest first. Viewing is not audited
    U->>W: Clicks a row
    W->>A: GET /api/audit-logs/:auditLogId
    A-->>W: 200 full entry with before and after, or 404 for another business
    U->>W: Export CSV
    alt more than 10000 matching rows
        W->>U: Explains the cap, narrow the filters
        Note over W,A: If the log grew past the cap after loading, the API answers 422 EXPORT_TOO_LARGE
    else within the cap
        W->>A: GET /api/audit-logs with format csv, same filters
        A->>D: Insert audit.exported with filters and row count
        A-->>W: 200 audit-log-today.csv with BOM, quoted cells, formula guard
    end
```

Caption: the owner reads only their own business, the super admin reads everything, viewing leaves no trace, and only an export is itself recorded.

---

## 10. Business creation and owner invitation by the super admin (FR-BIZ-1 to FR-BIZ-4)

```mermaid
sequenceDiagram
    autonumber
    actor SA as Super admin
    participant W as Web app
    participant A as API
    participant D as Database
    participant M as Email server
    actor OW as Owner to be

    SA->>W: Platform, New business, enters name, time zone, owner name and email
    W->>A: POST /api/businesses
    alt caller is not the super admin
        A-->>W: 403 FORBIDDEN
    else fields invalid
        A-->>W: 400 with the field names
    else email already used in any business or by the super admin
        A-->>W: 409 EMAIL_IN_USE
    else valid
        A->>D: One transaction: create business with defaults, create owner role ADMIN active no manager
        A->>D: Audit business.created and employee.created
        A-->>W: 201 with business id and owner id
        W->>SA: Business shown. There is no invitation email
    end
    SA->>OW: Tells the owner to open the sign-in page
    OW->>W: Enters own email
    W->>A: POST /api/auth/otp-requests
    A->>M: 6 digit code
    M-->>OW: Code arrives
    OW->>W: Enters the code, see section 2
    W->>A: POST /api/auth/sessions
    A-->>W: 201 session, owner lands on /admin
    OW->>W: Settings, then adds managers and employees
    W->>A: POST /api/employees for each person
    A->>D: Audit employee.created. Each person signs in the same way
```

Caption: the super admin creates a business and its owner in one step; the owner then signs in with an emailed code like everyone else and adds their own people.

---

## 11. Business suspension and reactivation (FR-BIZ-6, FR-BIZ-7)

```mermaid
flowchart TD
    A["Super admin chooses Suspend or Reactivate, optional reason"] --> B["PUT /api/businesses/:businessId/status with isSuspended"]
    B --> C{"Caller is the super admin and the business exists?"}
    C -->|"no"| X["403 FORBIDDEN or 404 NOT_FOUND"]
    C -->|"yes"| D{"Is the new state different from the stored one?"}
    D -->|"no"| E["200 unchanged, nothing audited"]
    D -->|"yes, suspend"| F["Set is_suspended true, audit business.suspended with reason"]
    F --> G["For every person of the business with an open workday: lock the person"]
    G --> H["End the running work or break interval now, set DAY_ENDED, recompute totals"]
    H --> I["Audit workday.closed per person, reason business_suspended, actor the super admin"]
    I --> J["Effects while suspended"]
    J --> J1["No sign-in codes, sign-in refused with 401"]
    J --> J2["Existing sessions and JurisOne tokens: every request 403 BUSINESS_SUSPENDED, audit security.suspended_business_request once per user and window"]
    J --> J3["Screen: Your company's access is paused, sign-out still works"]
    D -->|"yes, reactivate"| K["Set is_suspended false, audit business.reactivated"]
    K --> L["Everyone with a session works again at once. Closed days are not reopened"]
    L --> M["Employees may press Start work again; same date reopens the closed day"]
```

Caption: suspending a business stops the clocks of everyone working at that moment, so the suspension never counts as work, and reactivating changes nothing in the past.

---

## 12. Employee deactivation (FR-EMP-7 to FR-EMP-9)

```mermaid
flowchart TD
    A["Owner opens an employee and presses Deactivate, optional reason"] --> B["PUT /api/employees/:employeeId/status with isActive false"]
    B --> C{"Caller is the owner and the employee is in the business?"}
    C -->|"no"| X1["403 FORBIDDEN or 404 NOT_FOUND"]
    C -->|"yes"| D{"Is it the owner's own account?"}
    D -->|"yes"| R1["422 CANNOT_DEACTIVATE_SELF"]
    D -->|"no"| E{"Would the business be left without an active owner?"}
    E -->|"yes"| R2["422 LAST_ACTIVE_OWNER"]
    E -->|"no"| F{"Already inactive?"}
    F -->|"yes"| G["200 unchanged, nothing audited"]
    F -->|"no"| H["Set inactive"]
    H --> I["End every session of the person, their next request is 401"]
    I --> J{"Person has an open workday?"}
    J -->|"yes"| K["Lock the person, end the running work or break now, day completed, totals recomputed, audit workday.closed with the reason"]
    J -->|"no"| L["Nothing to close"]
    K --> M["Audit employee.deactivated with endedSessions and closedWorkdayId"]
    L --> M
    M --> N["Effects: no new sign-in codes, removed from the live board, past days stay visible and correctable, reports keep them, their reports keep them as manager"]
    R1 --> T["Audit employee.change_refused DENIED with the code"]
    R2 --> T
    N --> O["Later: PUT with isActive true gives employee.reactivated, they sign in with a new code"]
```

Caption: deactivating a person signs them out everywhere and stops their clock at that moment, while their history stays in place.

---

## 13. Embedded in JurisOne (summary only)

The full sequence is in [FLOW-JurisTrack-JurisOne-Connection.md](FLOW-JurisTrack-JurisOne-Connection.md). The short form:

```mermaid
flowchart LR
    U["JurisOne user"] --> JB["JurisOne backend gateway<br/>checks session, plan and permission"]
    JB -->|"one signed token per request, Bearer"| TK["JurisTrack API<br/>checks signature, issuer, audience,<br/>lifetime at most 10 minutes, single use"]
    TK -->|"token ok"| P["Find or create business and user,<br/>never adopt a standalone business by email"]
    TK -->|"token bad or replayed"| R["401 and audit integration.token_rejected"]
    P -->|"deactivated, login disabled, other business email, suspended"| F["403 and audit integration.user_refused"]
    P -->|"ok"| RULES["Same rules, same endpoints as standalone"]
```

Caption: embedded use changes only how the caller is proven; once the token is accepted every rule in this document applies unchanged.

A standalone business is attached to JurisOne only by the super admin (`PUT /api/businesses/:businessId/jurisone-link`, audit `integration.business_linked`; `DELETE` removes it, audit `integration.business_unlinked`).

---

## 14. Shifts and the roster (FR-SHIFT-1 to FR-SHIFT-14)

Time is calculated per shift, not per calendar day: a US shift 19:30-04:30 is one nine-hour workday whichever calendar dates it touches. Reference: Time Doctor counts "Worked today" per calendar day; JurisTrack deliberately attributes per shift.

### 14.1 Which workday does an instant belong to

```mermaid
flowchart TD
    A["An instant t: a START, or now when a screen reads the state"] --> B["For each shift date from D-2 to D+1 find the effective schedule of the employee"]
    B --> B1{"Roster entry for that date?"}
    B1 -->|"yes, a shift"| C["Use that shift"]
    B1 -->|"yes, week off"| C0["No occurrence that date"]
    B1 -->|"no"| B2{"Shift assigned from a date not after it?"}
    B2 -->|"yes"| C
    B2 -->|"no"| C1["Use the business default shift General day = the calendar day"]
    C1 --> C
    C --> D["Occurrence = scheduled window start to end in the business zone, DST aware<br/>attribution window = widened by the early and late grace"]
    D --> E{"Does an attribution window contain t?"}
    E -->|"one"| F["That occurrence, not outside the shift"]
    E -->|"several"| G["The one whose scheduled start is nearest to t"]
    E -->|"none"| H{"Is the calendar date of t a week off?"}
    H -->|"yes"| I["The occurrence the assigned shift would have had that date<br/>isOffDay true, isOutsideShift true, all work is extra time"]
    H -->|"no, a gap between shifts"| J["The nearest occurrence by distance to its scheduled window<br/>isOutsideShift true"]
    C0 --> E
```

Caption: the shift decides the date of the workday; a start inside the grace belongs to the shift, a start outside any shift is still allowed and counted but flagged.

### 14.2 The START decision and what a screen shows

```mermaid
flowchart TD
    A["START at time t"] --> B{"Open workday?"}
    B -->|"yes, any date"| R["Repeat: 200 alreadyApplied, nothing changes"]
    B -->|"no"| C["Attribution of t, section 14.1"]
    C --> D{"A workday already exists for that employee and shift date?"}
    D -->|"yes, ended"| E["Reopen it with a new work session<br/>the gap counts as neither productive nor break time"]
    D -->|"no"| F["Create the workday dated with the shift date<br/>snapshot: shift, scheduled start and end, outside shift, off day"]
    E --> G["Audit time.workday_reopened"]
    F --> H["Audit time.workday_started"]
    X["A screen or the board reads the current workday"] --> Y{"Open workday?"}
    Y -->|"yes"| Z["Show it, whatever its date: WORKING or ON_BREAK"]
    Y -->|"no"| Y2["Attribution of now"]
    Y2 --> Y3{"Workday for that shift date?"}
    Y3 -->|"yes"| Y4["DAY_ENDED with its own date and shift, productive and extra time counted in today's tiles"]
    Y3 -->|"no"| Y5{"Week off for that date?"}
    Y5 -->|"yes"| Y6["NOT_STARTED, isOffDay true, counted in the weekOff tile"]
    Y5 -->|"no"| Y7["NOT_STARTED with the shift of that occurrence"]
```

Caption: the same occurrence rule decides whether a START reopens the day or begins a new one and which workday a screen shows, so a shift that ended after midnight stays visible as DAY_ENDED until the next occurrence is nearer.

### 14.3 Example: a US shift across midnight

```mermaid
sequenceDiagram
    participant E as Employee in India
    participant S as JurisTrack
    E->>S: START at 19:25 on 5 Oct, five minutes early
    S-->>E: workday dated 5 Oct, US shift 19:30-04:30, scheduled 9 h, not outside the shift
    E->>S: BREAK 00:30 and RESUME 01:00 on 6 Oct
    S-->>E: still the workday of 5 Oct, break count 1
    E->>S: END at 04:45 on 6 Oct
    S-->>E: DAY_ENDED dated 5 Oct, productive 9 h 5 min of which extra 15 min, variance plus 5 min
    E->>S: END again at 05:00 retry or second device
    S-->>E: 200 alreadyApplied, no audit entry
    E->>S: START at 19:25 on 6 Oct
    S-->>E: a new workday dated 6 Oct, nothing of the 5 Oct shift is added
```

Caption: the 5 October shift and the 6 October shift are two workdays; work after midnight, a break after midnight and a repeated END never change a figure.

### 14.4 Managing shifts and the roster

```mermaid
flowchart TD
    A["Owner opens Shifts"] --> B["POST /api/shifts name, start, end, grace"]
    B --> C{"Name free, times valid?"}
    C -->|"no"| C1["409 SHIFT_NAME_IN_USE or 400"]
    C -->|"yes"| D["201 id, audit shift.created"]
    D --> E["PUT /api/employees/:id/shift from a date"]
    E --> F{"Shift active and date not in the past?"}
    F -->|"no"| F1["422 SHIFT_INACTIVE or SHIFT_EFFECTIVE_DATE_IN_PAST"]
    F -->|"yes"| G["Assignment history row, audit employee.shift_changed<br/>old workdays keep their snapshot"]
    G --> H["Owner sets weeks off and exceptions per date: PUT schedule-entries<br/>shift or null = week off, rotational"]
    H --> I{"Within 365 days and no workday on that date?"}
    I -->|"no"| I1["422 SCHEDULE_DATE_TOO_FAR or SCHEDULE_DATE_HAS_WORKDAY"]
    I -->|"yes"| J["Upsert, one audit entry schedule.entry_set per changed date"]
    J --> K["Manager reads GET /api/schedule-entries, employee reads GET /api/me/schedule"]
    D --> L["Deactivate a shift"]
    L --> M{"Default shift or active employees on it?"}
    M -->|"yes"| M1["422 DEFAULT_SHIFT_REQUIRED or SHIFT_IN_USE"]
    M -->|"no"| N["shift.status_changed"]
```

Caption: only the owner writes shifts, assignments and the roster; every change is audited and never rewrites a workday that already exists.
## 15. Employee login access (FR-EMP-10 to FR-EMP-14, FR-AUTH-15, FR-INT-8)

```mermaid
flowchart TD
    A["Owner opens an employee and presses Disable login or Enable login, optional reason"] --> B["PUT /api/employees/:employeeId/login-access with isEnabled"]
    B --> C{"Caller is the owner and the person is in the business?"}
    C -->|"no"| X1["403 FORBIDDEN or 404 NOT_FOUND"]
    C -->|"yes"| D{"Same state as now?"}
    D -->|"yes"| G["200 unchanged, nothing audited"]
    D -->|"no"| E{"Enable or disable?"}
    E -->|"enable"| ON["Set login on. Sign-in works again, nothing is reopened. Audit employee.login_enabled"]
    E -->|"disable"| F{"Is it the owner's own account?"}
    F -->|"yes"| R1["422 CANNOT_DISABLE_OWN_LOGIN, audit employee.change_refused DENIED"]
    F -->|"no"| H{"Person has an open workday?"}
    H -->|"yes"| K["Lock the person, end the running work or break now, day completed, totals recomputed, audit workday.closed with reason login_disabled"]
    H -->|"no"| L["Nothing to close"]
    K --> M["Set login off, end every session of the person"]
    L --> M
    M --> N["Audit employee.login_disabled with endedSessions and closedWorkdayId"]
    N --> O["Effects: next request 401 and refresh refused, code request and code check answer as for an unknown account, JurisOne token refused with 403, person stays active in list, board and reports"]
```

Caption: switching a login off signs the person out everywhere and stops their clock, but unlike Deactivate they stay in the business, the list and the reports.

The person's own attempts afterwards:

```mermaid
sequenceDiagram
    autonumber
    actor E as Employee with login off
    participant A as API
    participant D as Database
    E->>A: POST /api/auth/otp-requests with their email
    A->>D: Audit auth.sign_in_code_requested DENIED reason login_disabled, once per window
    A-->>E: 200 the usual text, no code is sent
    E->>A: POST /api/auth/sessions with any code
    A->>D: Audit auth.sign_in_failed reason login_disabled
    A-->>E: 401 That code is wrong or has expired, same as an unknown account
    E->>A: Request with an old cookie or a JurisOne token
    A-->>E: 401 for the cookie (session ended), 403 for the token with audit integration.user_refused login_disabled
```

Caption: the person learns nothing from the answers; only the audit log tells the owner why the sign-in was refused.

---

## Notes

- Mermaid check: see the report that came with this document version; the blocks were written to be plain flowchart, sequence and state diagrams with quoted labels and no parentheses in labels.
- Paths with ids are written `:workdayId` here instead of curly braces to keep the diagrams valid; the real paths are in `API.md`.
