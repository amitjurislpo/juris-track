# JurisTrack API: Team tab and time corrections

Module 5.4 (backend). This file holds the contract of the manager's Team tab and of time corrections until the lead merges it into `API.md`.
The conventions of `API.md` apply unchanged (base path, success body `{ ok, message?, data? }`, `application/problem+json` errors with `code`,
list parameters, UUID ids, ISO-8601 UTC timestamps, Origin check on cookie writes, audit in the same transaction).

## Endpoint list

| Method | Path | Roles | Purpose |
|---|---|---|---|
| GET | `/api/team/members` | ADMIN, MANAGER | Live board of the people in the caller's scope |
| GET | `/api/team/members/{employeeId}` | ADMIN, MANAGER | One person of the scope with their current time state |
| GET | `/api/team/members/{employeeId}/workdays` | ADMIN, MANAGER | One person's history (as `GET /api/workdays/mine`) |
| GET | `/api/workdays/{workdayId}` | ADMIN, MANAGER, EMPLOYEE | **Changed:** also a workday of somebody in the caller's scope |
| PATCH | `/api/workdays/{workdayId}/work-sessions/{sessionId}` | ADMIN, MANAGER | Correct a work interval |
| PATCH | `/api/workdays/{workdayId}/break-sessions/{sessionId}` | ADMIN, MANAGER | Correct a break interval |
| PUT | `/api/workdays/{workdayId}/status` | ADMIN, MANAGER | End an open (forgotten) workday |

EMPLOYEE and SUPER_ADMIN get 403 `FORBIDDEN` on every Team and correction endpoint (the SUPER_ADMIN also on `GET /api/workdays/{workdayId}`); signed out 401.

## Who sees and corrects whom (scope)

The reporting line (`users.manager_id`) decides, read when the request runs (not the manager stored on old workdays):

| Caller | Board (`GET /api/team/members`) | One person, their history, their workdays, corrections |
|---|---|---|
| MANAGER | `scope=direct` (default): their direct reports; `scope=indirect`: also the reports of those (one level further down) | direct and indirect reports (depth 2), whatever the board's switch shows |
| ADMIN (owner) | `scope=all` (default): everybody of the business; `scope=direct` / `indirect`: their own reporting line as for a manager | everybody of the business |

- The caller is never in their own list or scope: their own record is the personal section (`/api/me/time`, `/api/workdays/mine`), and
  `GET /api/team/members/{ownId}` answers 404.
- People above or beside a manager, people of another business, unknown ids and out-of-scope ids all answer **404** `NOT_FOUND` (never 403), so
  a caller cannot learn that a record exists.
- **Nobody corrects their own time**: 403 `CANNOT_CORRECT_OWN_TIME` ("You cannot correct your own time. Ask your manager or the owner to do it."),
  also for the owner. A manager's time is corrected by their own manager or the owner.
- Deactivated people stay in the scope for reading and correcting their past days (their history stays visible); the board leaves them out (below).

## Live board

### GET /api/team/members
Query:
- `scope` = `direct` | `indirect` (MANAGER, default `direct`) or `all` | `direct` | `indirect` (ADMIN, default `all`); any case. Anything else (also
  `all` for a manager) is 400 with field `scope`.
- `status` = `WORKING` | `ON_BREAK` | `NOT_STARTED` | `DAY_ENDED` | `all` (default; any case); else 400 (field `status`).
- `search`: first name, last name, "first last", email or employee code contains the text (case-insensitive).
- `sort` = `name` (default; first then last name) | `status` (ascending: WORKING, ON_BREAK, NOT_STARTED, DAY_ENDED) | `productiveSeconds` |
  `lastActivity` (people without activity first when ascending); `sortDirection` (`asc` default | `desc`); ties are ordered by name.
- `page`, `pageSize` (list parameters of `API.md`).

Who is on the board: the people of the scope who are **active**, plus a deactivated person whose workday is still open (should not happen:
deactivation closes it).

Each person's state is **today's** in the business time zone, exactly as `GET /api/me/time` decides it for oneself: the open workday whatever its
date (a night shift started yesterday is `WORKING` with yesterday's `date`), else today's ended workday (`DAY_ENDED`), else `NOT_STARTED`. A night
shift that ended after midnight is not today's: the person is `NOT_STARTED` again.

`data = { rows, total, page, pageSize, scope, serverTime, today, businessTimeZone, staleWorkdayHours, metrics }`:
- `total` = rows matching `status` and `search` (all pages); `scope` = the scope used (lower case); `today` = `YYYY-MM-DD` in the business time zone;
  `serverTime` lets the client measure its clock offset.
- row:
  ```
  { employee: { id, employeeCode, firstName, lastName, email, role, isActive, managerId, managerName },
    status: "NOT_STARTED" | "WORKING" | "ON_BREAK" | "DAY_ENDED",
    workdayId | null, date | null, startedAt | null, endedAt | null, isStale,
    closedProductiveSeconds, closedBreakSeconds, activeWorkStartedAt | null, activeBreakStartedAt | null,
    productiveSeconds, breakSeconds, spanSeconds, breakCount,
    lastActivity: { type: "STARTED_WORK" | "STARTED_BREAK" | "RESUMED_WORK" | "ENDED_DAY", at } | null }
  ```
  The time fields mean exactly what they mean in the workday snapshot of `API.md` (live values up to `serverTime`), so the client runs the same
  live timer: `closedProductiveSeconds + (now - activeWorkStartedAt)`, `closedBreakSeconds + (now - activeBreakStartedAt)`. `NOT_STARTED` rows have
  every number 0 and every time / id null. `isStale` ("not ended") = open and running longer than the business's `staleWorkdayHours`.
  `managerName` = "First Last" of the person's manager, null without one.
- `metrics = { total, working, onBreak, notStarted, dayEnded, stale, totalProductiveSeconds, totalBreakSeconds, averageProductiveSeconds }` over
  **everybody on the board in the chosen scope**: `status`, `search` and paging do not change them (the tiles stay stable while filtering).
  `averageProductiveSeconds` = `totalProductiveSeconds` divided by the people who started today (working + on break + day ended), rounded down; 0
  when nobody started.

The board is read in batches (people, workdays, work intervals, break intervals: one query each), whatever its size. Reading the board is **not**
audited (it refreshes every few seconds and shows only today's state); opening one person is (below).

## One person

### GET /api/team/members/{employeeId}
`data = { employee: <as in the board row>, serverTime, today, businessTimeZone, staleWorkdayHours, status, workday | null }`, where `status` and
`workday` (the full workday snapshot of `API.md` with `segments`) are chosen as `GET /api/me/time` chooses them for oneself. A deactivated person can
be opened. 404 `NOT_FOUND` ("Employee not found.") outside the scope (see above); 400 for a malformed id.
Audit (access): `access.team_member_viewed` (actor = caller, subject = the person, entity `user`).

### GET /api/team/members/{employeeId}/workdays
The person's workdays with the query parameters, sorting, defaults (`sortDirection` default `desc`) and answer of `GET /api/workdays/mine`:
`data = { rows, total, page, pageSize, range, from, to, totals, dailySeries }`. 404 outside the scope; 400 for a bad parameter (e.g. field `range`).
Audit (access): `access.team_member_workdays_viewed` (subject = the person, entity `user`, after `{ range, from, to }`).

### GET /api/workdays/{workdayId} (changed)
Besides one's own workdays, a manager or the owner reads a workday of anybody in their scope (rules above). Anything else, another business's and
an unknown id answer 404 `NOT_FOUND` ("Workday not found."). Reading somebody else's workday is audited: `access.workday_viewed` (subject = the
person, entity `workday`, after `{ date }`); reading one's own is not.

## Corrections

ADMIN or MANAGER, within the scope above, never on one's own records. The changed workday is answered as `data = <workday snapshot of API.md>`
(with every interval). Rules:
- **Reason**: `reason` is mandatory, 5 to 500 characters after trimming (400 field `reason`: "Give a reason of at least 5 characters.").
- **Times** are ISO-8601 instants with `Z` or an offset (`2026-10-22T09:30:00Z`, `2026-10-22T15:00:00+05:30`), stored in UTC to the microsecond;
  a time without an offset or anything else is 400 ("Send the time as an ISO-8601 instant, for example 2026-10-22T09:30:00Z.").
- No time may lie in the future (400 "Times cannot be in the future."); an interval must end **after** it starts (400 field `endedAt`: "The end time
  must be after the start time.").
- The intervals of a workday never overlap, work with work, break with break **and** work with break (422 `TIME_INTERVAL_OVERLAP`: "The corrected
  interval overlaps another work or break interval."); a running interval lasts until now, so only the latest interval may run. Intervals that only
  touch (one ends when the next starts) do not overlap.
- The first interval of a workday is a work interval (422 `WORKDAY_MUST_START_WITH_WORK`), so every break lies inside the day.
- A running interval keeps running: only its start moves (`endedAt` left out or `null`); a value for `endedAt` is 422 `INTERVAL_STILL_RUNNING`
  ("This interval is still running. Close the workday instead of setting an end time."). A closed interval stays closed (`endedAt: null` is 400
  "An end time is required.").
- The workday's `startedAt` becomes the start of its first interval and, when it has ended, its `endedAt` the end of its last interval. Its `date`
  never changes. A `DAY_ENDED` workday stays ended. Its stored totals are recomputed from the intervals (so they always equal the sum of the
  intervals); an open workday keeps none (it is computed live).
- Each correction locks the employee's row first, like `POST /api/me/time-events`, so it never interleaves with the employee's own start, break,
  resume or end; the state is read after the lock.

Error order: 401 / 403 role, then 400 for the body, then 404 (unknown workday, another business, out of scope) or 403 `CANNOT_CORRECT_OWN_TIME`,
then 404 for the interval, then the rules above (400 / 422).

### PATCH /api/workdays/{workdayId}/work-sessions/{sessionId}
### PATCH /api/workdays/{workdayId}/break-sessions/{sessionId}
Body: `{ startedAt?: string, endedAt?: string | null, reason: string }` (PATCH: a field left out stays as it is; `startedAt: null` is 400).
200 `{ ok, message: "Time corrected.", data: <workday> }`; when the times equal the stored ones nothing is written and the answer is 200 with
`message: "Nothing changed."`. 404 "Time record not found." when the interval does not exist, belongs to another workday or is of the other type
(a break id on `work-sessions`). Other errors as above.
Audit: `time.session_adjusted`, actor = caller, subject = the employee, entity `work_session` / `break_session` with the interval's id, the reason,
before / after `{ interval: { id, type: "WORK" | "BREAK", startedAt, endedAt }, workday: { id, date, status, startedAt, endedAt, productiveSeconds,
breakSeconds, spanSeconds, breakCount } }` (an open day's totals as of the correction).

### PUT /api/workdays/{workdayId}/status
Body: `{ status: "DAY_ENDED" (any case; nothing else can be set, 400 field status), endedAt?: string, reason: string }`. Ends an open workday,
typically a forgotten (stale) one: the running work or break interval ends at `endedAt` and the day becomes `DAY_ENDED` with recomputed totals.
Without `endedAt` the end is **now** while an interval is running (the old app's rule), otherwise the end of the last interval. `endedAt` may not be in
the future nor before the last recorded activity (the start of the running interval or the end of any interval): 400 field `endedAt` ("The end time
cannot be before the last recorded activity."). Any open workday can be ended, stale or not.
200 `{ ok, message: "Workday ended.", data: <workday> }`. Idempotent: a workday that has already ended is left as it is, answered 200 with
`message: "This workday had already ended."` and no audit entry. Afterwards the employee may start again; a day ended on today's date is reopened by
their next `START` as usual.
Audit: `time.workday_closed`, entity `workday`, the reason, before `{ workday: <summary as above> }`, after `{ workday: <summary>, closedIntervals:
[{ id, type, startedAt, endedAt }] }`. (Closing because of a deactivation keeps its own action `workday.closed`.)

## Error codes added

| Status | Code | When |
|---|---|---|
| 403 | `CANNOT_CORRECT_OWN_TIME` | a correction of one's own workday |
| 422 | `TIME_INTERVAL_OVERLAP` | the corrected interval overlaps another interval of the workday |
| 422 | `INTERVAL_STILL_RUNNING` | an end time for the running interval |
| 422 | `WORKDAY_MUST_START_WITH_WORK` | the first interval would be a break |

## Audit actions added

`time.session_adjusted`, `time.workday_closed` (corrections, written by `TimeCorrectionAudit`), `access.team_member_viewed`,
`access.team_member_workdays_viewed`, `access.workday_viewed` (reads of somebody else's data, written by `TeamAccessAudit`).
