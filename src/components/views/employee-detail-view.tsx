import type { ReactNode } from "react";
import { AdjustIntervalButton, CloseWorkdayButton } from "@/components/admin/time-correction-controls";
import { RangeFilter } from "@/components/filters/url-filters";
import { DailyChart } from "@/components/time/daily-chart";
import { EmployeeLiveStatus } from "@/components/time/employee-live-status";
import { Timeline } from "@/components/time/timeline";
import { Badge, StatusBadge } from "@/components/ui/badge";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { DescriptionList, EmptyState, PageHeader, StatCard } from "@/components/ui/misc";
import type { SessionUser } from "@/lib/auth/session";
import { ROLE_LABELS } from "@/lib/auth/roles";
import { cn } from "@/lib/cn";
import { orNotFound } from "@/lib/not-found";
import { formatDateKey, formatDateTime, formatDuration, formatTime } from "@/lib/time/format";
import { resolveRange } from "@/lib/time/ranges";
import { getEmployee } from "@/server/services/employees.service";
import { dailySeries, getWorkdayRecords, totalsOf } from "@/server/services/reports.service";
import { orgToday } from "@/server/services/settings.service";
import { getEmployeeState } from "@/server/services/time-tracking.service";
import { getViewerTimezone } from "@/server/viewer-timezone";

type Params = Record<string, string | string[] | undefined>;

/**
 * Employee profile + productivity view shared by managers (scoped) and
 * admins. Admin-only controls are passed in and rendered only for admins.
 */
export async function EmployeeDetailView({
  actor,
  employeeId,
  searchParams,
  back,
  adminPanel,
}: {
  actor: SessionUser;
  employeeId: string;
  searchParams: Params;
  back: { href: string; label: string };
  adminPanel?: ReactNode;
}) {
  const employee = await orNotFound(getEmployee(actor, employeeId)); // 404 when missing or out of scope
  const { today } = await orgToday();
  const timezone = await getViewerTimezone();
  const range = resolveRange(searchParams, today, "week");
  const [state, records] = await Promise.all([
    getEmployeeState(employee.id),
    getWorkdayRecords(actor, { from: range.from, to: range.to, employeeId: employee.id, withSegments: true }),
  ]);
  const totals = totalsOf(records);
  const isAdmin = actor.role === "ADMIN";
  const nowMs = Date.parse(state.serverNow);

  return (
    <>
      <PageHeader
        back={back}
        title={
          <span className="flex flex-wrap items-center gap-3">
            {employee.firstName} {employee.lastName}
            {!employee.isActive && <Badge tone="danger">Inactive</Badge>}
            {employee.isDemo && <Badge tone="accent">Demo</Badge>}
          </span>
        }
        description={[employee.employeeCode, employee.team?.name ?? "Unassigned", ROLE_LABELS[employee.role]].filter(Boolean).join(" · ")}
      />

      <div className="grid gap-6 xl:grid-cols-[1fr_380px]">
        <Card>
          <CardHeader title="Current status" description={formatDateKey(state.today)} />
          <CardBody>
            {employee.isActive ? (
              <EmployeeLiveStatus employeeId={employee.id} initial={state} displayTimezone={timezone} />
            ) : (
              <p className="text-sm text-ink-3">This account is deactivated and cannot record time.</p>
            )}
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Profile" />
          <CardBody>
            <DescriptionList
              items={[
                { label: "Email", value: <span className="break-all">{employee.email}</span> },
                { label: "Employee ID", value: employee.employeeCode ?? "—" },
                { label: "Team", value: employee.team?.name ?? "Unassigned" },
                {
                  label: "Manager",
                  value: employee.team?.manager ? `${employee.team.manager.firstName} ${employee.team.manager.lastName}` : "—",
                },
                { label: "Role", value: ROLE_LABELS[employee.role] },
                { label: "Last sign-in", value: formatDateTime(employee.lastLoginAt, timezone) },
              ]}
            />
          </CardBody>
        </Card>
      </div>

      {adminPanel && <div className="mt-6">{adminPanel}</div>}

      <div className="mt-8 mb-4 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-ink">Productivity history</h2>
          <p className="text-sm text-ink-3">
            {formatDateKey(range.from)} – {formatDateKey(range.to)}
          </p>
        </div>
        <RangeFilter range={range} />
      </div>

      <div className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Workdays" value={records.length} />
        <StatCard label="Productive" value={formatDuration(totals.productiveSeconds)} tone="working" hint={records.length ? `Avg ${formatDuration(totals.productiveSeconds / records.length)} / day` : undefined} />
        <StatCard label="Break" value={formatDuration(totals.breakSeconds)} tone="break" hint={`${totals.breakCount} breaks`} />
        <StatCard label="Worked span" value={formatDuration(totals.spanSeconds)} />
      </div>

      {range.from !== range.to && (
        <Card className="mb-6">
          <CardHeader title="Daily productive hours" />
          <CardBody>
            <DailyChart points={dailySeries(records, range.from, range.to)} />
          </CardBody>
        </Card>
      )}

      <Card>
        <CardHeader title="Workday history" description={isAdmin ? "Expand a day to see and adjust its intervals. All adjustments are audited." : "Expand a day to see its intervals."} />
        {records.length === 0 ? (
          <EmptyState icon="calendar" title="No workdays in this period" />
        ) : (
          <ul className="divide-y divide-border">
            {records.map((r) => (
              <li key={r.workdayId}>
                <details className="group">
                  <summary className="grid cursor-pointer list-none grid-cols-2 items-center gap-x-4 gap-y-2 px-5 py-4 hover:bg-surface-2 md:grid-cols-[150px_150px_1fr_repeat(3,90px)_20px] [&::-webkit-details-marker]:hidden">
                    <span className="font-medium text-ink">{formatDateKey(r.date)}</span>
                    <span className="flex items-center gap-2">
                      <StatusBadge status={r.status} />
                      {r.isStale && <Badge tone="warning">Not ended</Badge>}
                    </span>
                    <span className="col-span-2 md:col-span-1">
                      {r.segments && <Timeline segments={r.segments} nowMs={nowMs} timezone={timezone} showList={false} compact />}
                    </span>
                    <span className="tabular text-sm">
                      <span className="block text-[11px] text-ink-3 uppercase">Productive</span>
                      <span className="font-semibold">{formatDuration(r.productiveSeconds)}</span>
                    </span>
                    <span className="tabular text-sm">
                      <span className="block text-[11px] text-ink-3 uppercase">Break</span>
                      {formatDuration(r.breakSeconds)}
                    </span>
                    <span className="tabular text-sm">
                      <span className="block text-[11px] text-ink-3 uppercase">Hours</span>
                      {formatTime(r.startedAt, timezone)}–{r.endedAt ? formatTime(r.endedAt, timezone) : "now"}
                    </span>
                    <span className="hidden text-ink-3 transition-transform group-open:rotate-90 md:block" aria-hidden="true">
                      ›
                    </span>
                  </summary>
                  <div className="space-y-4 bg-surface-2 px-5 pt-2 pb-5">
                    {r.segments && <Timeline segments={r.segments} nowMs={nowMs} timezone={timezone} showList={false} />}
                    <div className="overflow-hidden rounded-lg border border-border bg-surface">
                      <table className="w-full text-sm">
                        <thead className="bg-surface-2 text-xs text-ink-3 uppercase">
                          <tr>
                            <th className="px-4 py-2 text-left font-medium">Type</th>
                            <th className="px-4 py-2 text-left font-medium">Start</th>
                            <th className="px-4 py-2 text-left font-medium">End</th>
                            <th className="px-4 py-2 text-right font-medium">Duration</th>
                            {isAdmin && <th className="px-4 py-2" />}
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                          {(r.segments ?? []).map((s) => (
                            <tr key={s.id}>
                              <td className="px-4 py-2">
                                <span className="flex items-center gap-2 font-medium">
                                  <span className={cn("size-2.5 rounded-sm", s.kind === "WORK" ? "bg-mark-work" : "bg-mark-break")} aria-hidden="true" />
                                  {s.kind === "WORK" ? "Work" : "Break"}
                                </span>
                              </td>
                              <td className="tabular px-4 py-2">{formatTime(s.startedAt, timezone)}</td>
                              <td className="tabular px-4 py-2">{s.endedAt ? formatTime(s.endedAt, timezone) : "In progress"}</td>
                              <td className="tabular px-4 py-2 text-right">
                                {formatDuration(Math.floor(((s.endedAt ? Date.parse(s.endedAt) : nowMs) - Date.parse(s.startedAt)) / 1000))}
                              </td>
                              {isAdmin && (
                                <td className="px-4 py-2 text-right">
                                  <AdjustIntervalButton kind={s.kind} sessionId={s.id} startedAt={s.startedAt} endedAt={s.endedAt} timezone={timezone} />
                                </td>
                              )}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-ink-2">
                      <span className="tabular">
                        Span {formatDuration(r.spanSeconds)} · {r.breakCount} break{r.breakCount === 1 ? "" : "s"}
                      </span>
                      {isAdmin && r.status !== "DAY_ENDED" && <CloseWorkdayButton workdayId={r.workdayId} timezone={timezone} />}
                    </div>
                  </div>
                </details>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}
