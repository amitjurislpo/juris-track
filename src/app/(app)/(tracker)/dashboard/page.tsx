import type { Metadata } from "next";
import Link from "next/link";
import { DailyChart } from "@/components/time/daily-chart";
import { TimerPanel } from "@/components/time/timer-panel";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { PageHeader } from "@/components/ui/misc";
import { requireRole } from "@/lib/auth/guards";
import { formatDateKey, formatDuration } from "@/lib/time/format";
import { addDays } from "@/lib/time/tz";
import { dailySeries, getWorkdayRecords, totalsOf } from "@/server/services/reports.service";
import { getEmployeeState } from "@/server/services/time-tracking.service";
import { getViewerTimezone } from "@/server/viewer-timezone";

export const metadata: Metadata = { title: "My Dashboard" };

function greeting(hour: number) {
  if (hour < 12) return "Good morning";
  if (hour < 17) return "Good afternoon";
  return "Good evening";
}

export default async function EmployeeDashboard() {
  const user = await requireRole("EMPLOYEE", "MANAGER");
  const [state, viewTz] = await Promise.all([getEmployeeState(user.id), getViewerTimezone()]);
  const from = addDays(state.today, -13);
  const records = await getWorkdayRecords(user, { from, to: state.today, employeeId: user.id });
  const series = dailySeries(records, from, state.today);
  const past = records.filter((r) => r.date !== state.today && r.status === "DAY_ENDED");
  const avg = past.length ? totalsOf(past).productiveSeconds / past.length : 0;
  const hour = Number(new Intl.DateTimeFormat("en-GB", { hour: "numeric", hourCycle: "h23", timeZone: viewTz }).format(new Date(state.serverNow)));

  return (
    <>
      <PageHeader title={`${greeting(hour)}, ${user.firstName}`} description={formatDateKey(state.today)} />
      <TimerPanel initialState={state} displayTimezone={viewTz} />
      <Card className="mt-6">
        <CardHeader
          title="Productive hours — last 14 days"
          description={past.length ? `Average ${formatDuration(avg)} per completed workday` : undefined}
          actions={
            <Link href="/my-history" className="text-sm font-medium text-ink-2 hover:text-ink">
              View history →
            </Link>
          }
        />
        <CardBody>
          <DailyChart points={series} />
        </CardBody>
      </Card>
    </>
  );
}
