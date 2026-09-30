"use client";

import { useEffect, useState } from "react";
import { StatusBadge, Badge } from "@/components/ui/badge";
import { formatClock, formatDuration, formatTime } from "@/lib/time/format";
import { liveTotals } from "@/lib/time/live";
import { ACTIVITY_LABELS, type EmployeeTimeState } from "@/lib/time/types";
import { Timeline } from "./timeline";
import { useServerNow } from "./use-server-now";

/** Read-only live view of another employee's current workday. */
export function EmployeeLiveStatus({
  employeeId,
  initial,
  displayTimezone,
}: {
  employeeId: string;
  initial: EmployeeTimeState;
  displayTimezone: string;
}) {
  const [state, setState] = useState(initial);
  const nowMs = useServerNow(state.serverNow);

  useEffect(() => {
    const load = async () => {
      try {
        const res = await fetch(`/api/employees/${employeeId}/status`, { cache: "no-store" });
        const body = (await res.json()) as { ok: boolean; data?: EmployeeTimeState };
        if (body.ok && body.data) setState(body.data);
      } catch {
        /* keep last known state; next poll retries */
      }
    };
    const timer = setInterval(load, 20_000);
    return () => clearInterval(timer);
  }, [employeeId]);

  const wd = state.workday;
  const live = wd ? liveTotals(wd, nowMs) : null;
  const tz = displayTimezone;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-3">
        <StatusBadge status={state.status} size="lg" />
        {wd?.isStale && <Badge tone="warning">Workday not ended</Badge>}
        {wd && <span className="text-sm text-ink-3">{`${ACTIVITY_LABELS[wd.lastActivity.kind]} at ${formatTime(wd.lastActivity.at, tz)}`}</span>}
      </div>
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <div>
          <p className="text-xs font-medium tracking-wide text-ink-3 uppercase">Productive today</p>
          <p className="tabular mt-1 font-mono text-2xl font-semibold text-ink" suppressHydrationWarning>
            {formatClock(live?.productiveSeconds ?? 0)}
          </p>
        </div>
        <div>
          <p className="text-xs font-medium tracking-wide text-ink-3 uppercase">Break today</p>
          <p className="tabular mt-1 font-mono text-2xl font-semibold text-ink" suppressHydrationWarning>
            {formatClock(live?.breakSeconds ?? 0)}
          </p>
        </div>
        <div>
          <p className="text-xs font-medium tracking-wide text-ink-3 uppercase">Started</p>
          <p className="tabular mt-1 text-lg font-semibold text-ink">{formatTime(wd?.startedAt, tz)}</p>
        </div>
        <div>
          <p className="text-xs font-medium tracking-wide text-ink-3 uppercase">Breaks</p>
          <p className="tabular mt-1 text-lg font-semibold text-ink">
            {wd?.breakCount ?? 0}
            {live && live.breakSeconds > 0 && <span className="ml-1 text-sm font-normal text-ink-3">({formatDuration(live.breakSeconds)})</span>}
          </p>
        </div>
      </div>
      {wd && <Timeline segments={wd.segments} nowMs={nowMs} timezone={tz} showList={false} />}
    </div>
  );
}
