import type { Metadata } from "next";
import { Timeline } from "@/components/time/timeline";
import { StatusBadge } from "@/components/ui/badge";
import { ButtonLink } from "@/components/ui/button";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { EmptyState, PageHeader, StatCard } from "@/components/ui/misc";
import { Table, Td, Th, THead, Tr } from "@/components/ui/table";
import { requireRole } from "@/lib/auth/guards";
import { formatDateKey, formatDuration, formatTime } from "@/lib/time/format";
import { getEmployeeState } from "@/server/services/time-tracking.service";
import { getViewerTimezone } from "@/server/viewer-timezone";

export const metadata: Metadata = { title: "My Time" };

export default async function MyTimePage() {
  const user = await requireRole("EMPLOYEE", "MANAGER");
  const [state, viewTz] = await Promise.all([getEmployeeState(user.id), getViewerTimezone()]);
  const wd = state.workday;
  const tz = viewTz;
  const nowMs = Date.parse(state.serverNow);

  return (
    <>
      <PageHeader
        title="My Time"
        description={`Every recorded interval for ${formatDateKey(wd?.date ?? state.today)}. Values reflect ${formatTime(state.serverNow, tz)}.`}
        actions={<ButtonLink href="/dashboard">Open timer</ButtonLink>}
      />
      {!wd ? (
        <Card>
          <EmptyState
            icon="clock"
            title="You haven't started today"
            description="Start your workday from the dashboard. Your work and break intervals will be listed here."
            action={<ButtonLink href="/dashboard">Go to dashboard</ButtonLink>}
          />
        </Card>
      ) : (
        <div className="space-y-6">
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            <StatCard label="Status" value={<StatusBadge status={wd.status} size="lg" />} />
            <StatCard label="Productive" value={formatDuration(wd.productiveSeconds)} tone="working" />
            <StatCard label="Break" value={formatDuration(wd.breakSeconds)} tone="break" hint={`${wd.breakCount} break${wd.breakCount === 1 ? "" : "s"}`} />
            <StatCard label="Workday start" value={formatTime(wd.startedAt, tz)} />
            <StatCard label="Worked span" value={formatDuration(wd.spanSeconds)} hint={wd.endedAt ? `Ended ${formatTime(wd.endedAt, tz)}` : "In progress"} />
          </div>
          <Card>
            <CardHeader title="Timeline" />
            <CardBody>
              <Timeline segments={wd.segments} nowMs={nowMs} timezone={tz} showList={false} />
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Intervals" description="Productive time is the sum of work intervals; break time is the sum of break intervals." />
            <Table>
              <THead>
                <tr>
                  <Th>#</Th>
                  <Th>Type</Th>
                  <Th>Start</Th>
                  <Th>End</Th>
                  <Th align="right">Duration</Th>
                </tr>
              </THead>
              <tbody>
                {wd.segments.map((s, i) => {
                  const dur = Math.floor(((s.endedAt ? Date.parse(s.endedAt) : nowMs) - Date.parse(s.startedAt)) / 1000);
                  return (
                    <Tr key={s.id}>
                      <Td className="tabular text-ink-3">{i + 1}</Td>
                      <Td>
                        <span className="flex items-center gap-2 font-medium">
                          <span className={s.kind === "WORK" ? "size-2.5 rounded-sm bg-mark-work" : "size-2.5 rounded-sm bg-mark-break"} aria-hidden="true" />
                          {s.kind === "WORK" ? "Work" : "Break"}
                        </span>
                      </Td>
                      <Td className="tabular">{formatTime(s.startedAt, tz)}</Td>
                      <Td className="tabular">{s.endedAt ? formatTime(s.endedAt, tz) : "In progress"}</Td>
                      <Td align="right">{formatDuration(dur)}</Td>
                    </Tr>
                  );
                })}
              </tbody>
            </Table>
          </Card>
        </div>
      )}
    </>
  );
}
