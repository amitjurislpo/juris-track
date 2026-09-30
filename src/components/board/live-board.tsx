"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useServerNow } from "@/components/time/use-server-now";
import { Badge, StatusBadge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { SelectInput, TextInput } from "@/components/ui/form";
import { Avatar, EmptyState, StatCard } from "@/components/ui/misc";
import { Spinner } from "@/components/ui/spinner";
import { Table, Td, Th, THead, Tr } from "@/components/ui/table";
import { cn } from "@/lib/cn";
import { formatDateKey, formatDuration, formatTime } from "@/lib/time/format";
import { liveTotals } from "@/lib/time/live";
import { zoneAbbr } from "@/lib/time/zones";
import { ACTIVITY_LABELS, LIVE_STATUS_LABELS, LIVE_STATUSES, type LiveStatus } from "@/lib/time/types";
import type { BoardData, BoardRow } from "@/server/services/board.service";

const POLL_MS = 15_000;

interface Filters {
  date: string;
  teamId: string;
  employeeId: string;
  status: string;
}

export interface BoardOption {
  value: string;
  label: string;
}

/**
 * Live status board. Values tick locally from each row's timestamps and the
 * whole board re-syncs from the server every 15 s (and on tab focus), so
 * status changes made by employees appear without a manual refresh.
 */
export function LiveBoard({
  initial,
  teams,
  employees,
  detailBase,
  initialFilters,
  displayTimezone,
}: {
  initial: BoardData;
  teams: BoardOption[];
  employees: BoardOption[];
  detailBase: string;
  initialFilters: Partial<Filters>;
  /** Display only; dates and filters use the organization calendar. */
  displayTimezone: string;
}) {
  const [data, setData] = useState(initial);
  const [filters, setFilters] = useState<Filters>({
    date: initialFilters.date ?? initial.date,
    teamId: initialFilters.teamId ?? "",
    employeeId: initialFilters.employeeId ?? "",
    status: initialFilters.status ?? "",
  });
  const [loading, setLoading] = useState(false);
  const [stale, setStale] = useState(false);
  const router = useRouter();
  const pathname = usePathname();
  const nowMs = useServerNow(data.serverNow);
  const seq = useRef(0);

  const load = useCallback(
    async (f: Filters, showSpinner: boolean) => {
      const id = ++seq.current;
      if (showSpinner) setLoading(true);
      const qs = new URLSearchParams(Object.entries(f).filter(([, v]) => v) as [string, string][]);
      try {
        const res = await fetch(`/api/board?${qs.toString()}`, { cache: "no-store" });
        if (res.status === 401) return router.replace("/login");
        const body = (await res.json()) as { ok: boolean; data?: BoardData };
        if (id === seq.current && body.ok && body.data) {
          setData(body.data);
          setStale(false);
        }
      } catch {
        if (id === seq.current) setStale(true);
      } finally {
        if (id === seq.current) setLoading(false);
      }
    },
    [router],
  );

  useEffect(() => {
    const timer = setInterval(() => void load(filters, false), POLL_MS);
    const onVisible = () => {
      if (document.visibilityState === "visible") void load(filters, false);
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [filters, load]);

  function change(patch: Partial<Filters>) {
    const next = { ...filters, ...patch };
    setFilters(next);
    const qs = new URLSearchParams(Object.entries(next).filter(([k, v]) => v && !(k === "date" && v === data.today)) as [string, string][]);
    router.replace(`${pathname}${qs.size ? `?${qs.toString()}` : ""}`, { scroll: false });
    void load(next, true);
  }

  const rows = useMemo(
    () =>
      data.rows.map((r) => ({
        row: r,
        live: r.workday ? liveTotals(r.workday, data.isToday ? nowMs : Date.parse(data.serverNow)) : null,
      })),
    [data, nowMs],
  );

  const liveProductive = useMemo(() => {
    // Metrics tiles tick too: server totals + time elapsed since the snapshot for open intervals.
    const elapsed = Math.max(0, Math.floor((nowMs - Date.parse(data.serverNow)) / 1000));
    const all = data.metrics;
    return {
      productive: all.productiveSeconds + (data.isToday ? all.working * elapsed : 0),
      break: all.breakSeconds + (data.isToday ? all.onBreak * elapsed : 0),
    };
  }, [data, nowMs]);

  const m = data.metrics;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
        <StatCard label="Working" value={m.working} tone="working" />
        <StatCard label="On break" value={m.onBreak} tone="break" />
        <StatCard label="Not started" value={m.notStarted} tone="idle" />
        <StatCard label="Day ended" value={m.dayEnded} tone="ended" />
        <StatCard label="Productive hours" value={<span suppressHydrationWarning>{formatDuration(liveProductive.productive)}</span>} hint={`${m.total} employees`} />
        <StatCard label="Break hours" value={<span suppressHydrationWarning>{formatDuration(liveProductive.break)}</span>} />
      </div>

      <Card>
        <div className="flex flex-wrap items-end gap-3 border-b border-border px-5 py-4">
          <TextInput
            type="date"
            label="Date"
            value={filters.date}
            max={data.today}
            onChange={(e) => e.target.value && change({ date: e.target.value })}
            className="h-9 w-44"
          />
          <SelectInput label="Team" value={filters.teamId} onChange={(e) => change({ teamId: e.target.value })} className="h-9" fieldClassName="min-w-44">
            <option value="">All teams</option>
            {teams.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </SelectInput>
          <SelectInput label="Employee" value={filters.employeeId} onChange={(e) => change({ employeeId: e.target.value })} className="h-9" fieldClassName="min-w-48">
            <option value="">All employees</option>
            {employees.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </SelectInput>
          <SelectInput label="Status" value={filters.status} onChange={(e) => change({ status: e.target.value })} className="h-9" fieldClassName="min-w-40">
            <option value="">All statuses</option>
            {LIVE_STATUSES.map((s) => (
              <option key={s} value={s}>
                {LIVE_STATUS_LABELS[s]}
              </option>
            ))}
          </SelectInput>
          <div className="ml-auto flex items-center gap-2 pb-2 text-xs text-ink-3">
            {loading ? (
              <Spinner className="size-4" />
            ) : (
              <span className={cn("size-2 rounded-full", stale ? "bg-warning" : "bg-st-working")} aria-hidden="true" />
            )}
            <span suppressHydrationWarning>
              {stale ? "Reconnecting…" : data.isToday ? `Live · updated ${formatTime(data.serverNow, displayTimezone)} ${zoneAbbr(displayTimezone, new Date(data.serverNow))}` : `Showing ${formatDateKey(data.date)}`}
            </span>
          </div>
        </div>

        {rows.length === 0 ? (
          <EmptyState icon="users" title="No employees match these filters" description="Adjust the filters, or assign employees to your teams." />
        ) : (
          <Table>
            <THead>
              <tr>
                <Th>Employee</Th>
                <Th>Team</Th>
                <Th>Status</Th>
                <Th align="right">Productive</Th>
                <Th align="right">Break</Th>
                <Th>Start</Th>
                <Th>End</Th>
                <Th align="right">Breaks</Th>
                <Th>Last activity</Th>
              </tr>
            </THead>
            <tbody>
              {rows.map(({ row, live }) => (
                <BoardTableRow key={row.employee.id} row={row} live={live} timezone={displayTimezone} detailBase={detailBase} />
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </div>
  );
}

function BoardTableRow({
  row,
  live,
  timezone,
  detailBase,
}: {
  row: BoardRow;
  live: ReturnType<typeof liveTotals> | null;
  timezone: string;
  detailBase: string;
}) {
  const wd = row.workday;
  const status: LiveStatus = row.status;
  return (
    <Tr>
      <Td>
        <Link href={`${detailBase}/${row.employee.id}`} className="flex items-center gap-3 font-medium hover:underline">
          <Avatar name={row.employee} />
          <span className="min-w-0">
            <span className="block truncate">
              {row.employee.firstName} {row.employee.lastName}
            </span>
            {row.employee.employeeCode && <span className="block text-xs font-normal text-ink-3">{row.employee.employeeCode}</span>}
          </span>
        </Link>
      </Td>
      <Td className="whitespace-nowrap text-ink-2">{row.team?.name ?? "—"}</Td>
      <Td>
        <span className="flex items-center gap-2">
          <StatusBadge status={status} />
          {wd?.isStale && <Badge tone="warning">Not ended</Badge>}
        </span>
      </Td>
      <Td align="right" className="font-semibold" suppressHydrationWarning>
        {live ? formatDuration(live.productiveSeconds) : "—"}
      </Td>
      <Td align="right" suppressHydrationWarning>
        {live ? formatDuration(live.breakSeconds) : "—"}
      </Td>
      <Td className="tabular">{formatTime(wd?.startedAt, timezone)}</Td>
      <Td className="tabular">{formatTime(wd?.endedAt, timezone)}</Td>
      <Td align="right">{wd ? wd.breakCount : "—"}</Td>
      <Td className="whitespace-nowrap text-ink-2">
        {wd ? `${ACTIVITY_LABELS[wd.lastActivity.kind]} · ${formatTime(wd.lastActivity.at, timezone)}` : "—"}
      </Td>
    </Tr>
  );
}
