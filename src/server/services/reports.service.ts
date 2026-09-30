import type { Prisma } from "@/generated/prisma/client";
import { db } from "@/lib/db";
import type { SessionUser } from "@/lib/auth/session";
import { computeTotals } from "@/lib/time/calc";
import { dateKeyToDb, dbToDateKey, eachDay, type DateKey } from "@/lib/time/tz";
import type { LiveStatus, TimelineSegment } from "@/lib/time/types";
import { getSettings } from "./settings.service";
import { employeeScopeWhere } from "./scope.service";
import { buildSegments, liveStatusOf, workdayWithSessions } from "./time-tracking.service";

/**
 * Reporting layer. `getWorkdayRecords` returns flat, typed rows that every
 * view (tables, charts, and the CSV export) renders from, so new export
 * formats need no schema or query changes.
 */

export interface WorkdayRecord {
  workdayId: string;
  date: DateKey;
  employee: { id: string; firstName: string; lastName: string; employeeCode: string | null };
  team: { id: string; name: string } | null;
  status: LiveStatus;
  isStale: boolean;
  startedAt: string;
  endedAt: string | null;
  productiveSeconds: number;
  breakSeconds: number;
  spanSeconds: number;
  breakCount: number;
  segments?: TimelineSegment[];
}

export interface RecordQuery {
  from: DateKey;
  to: DateKey;
  teamId?: string;
  employeeId?: string;
  /** Include work/break intervals for timelines. */
  withSegments?: boolean;
}

export async function getWorkdayRecords(actor: SessionUser, q: RecordQuery): Promise<WorkdayRecord[]> {
  const settings = await getSettings();
  const now = new Date();

  const where: Prisma.WorkdayWhereInput = {
    date: { gte: dateKeyToDb(q.from), lte: dateKeyToDb(q.to) },
    // Everyone may read their own records; otherwise the role scope applies.
    employee: q.employeeId === actor.id ? { id: actor.id } : employeeScopeWhere(actor),
    ...(q.teamId ? { teamId: q.teamId } : {}),
    ...(q.employeeId ? { employeeId: q.employeeId } : {}),
  };

  const base = await db.workday.findMany({
    where,
    orderBy: [{ date: "desc" }, { startedAt: "asc" }],
    select: {
      id: true,
      date: true,
      status: true,
      startedAt: true,
      endedAt: true,
      productiveSeconds: true,
      breakSeconds: true,
      breakCount: true,
      employee: { select: { id: true, firstName: true, lastName: true, employeeCode: true } },
      team: { select: { id: true, name: true } },
    },
  });

  // Open workdays (and any without cached totals, or when timelines are
  // requested) are computed live from their sessions.
  const needSessions = base.filter((w) => q.withSegments || w.status !== "COMPLETED" || w.productiveSeconds == null).map((w) => w.id);
  const withSessions = needSessions.length
    ? await db.workday.findMany({ where: { id: { in: needSessions } }, include: workdayWithSessions })
    : [];
  const sessionsById = new Map(withSessions.map((w) => [w.id, w]));

  return base.map((w) => {
    const full = sessionsById.get(w.id);
    const date = dbToDateKey(w.date);
    const span = Math.max(0, Math.floor(((w.endedAt ?? now).getTime() - w.startedAt.getTime()) / 1000));
    const totals =
      w.status === "COMPLETED" && w.productiveSeconds != null
        ? { productiveSeconds: w.productiveSeconds, breakSeconds: w.breakSeconds ?? 0, breakCount: w.breakCount ?? 0, spanSeconds: span }
        : full
          ? computeTotals(full, full.workSessions, full.breakSessions, now)
          : { productiveSeconds: 0, breakSeconds: 0, breakCount: 0, spanSeconds: span };
    const open = w.status !== "COMPLETED";
    return {
      workdayId: w.id,
      date,
      employee: w.employee,
      team: w.team,
      status: liveStatusOf(w.status),
      isStale: open && totals.spanSeconds > settings.staleWorkdayHours * 3600,
      startedAt: w.startedAt.toISOString(),
      endedAt: w.endedAt?.toISOString() ?? null,
      ...totals,
      ...(q.withSegments && full ? { segments: buildSegments(full) } : {}),
    };
  });
}

// ─── Aggregation ─────────────────────────────────────────────────────────────

export type GroupBy = "workday" | "employee" | "team" | "date";

export interface AggregateRow {
  key: string;
  label: string;
  sublabel?: string;
  href?: { kind: "employee" | "team"; id: string };
  workdays: number;
  productiveSeconds: number;
  breakSeconds: number;
  spanSeconds: number;
  breakCount: number;
  avgProductiveSeconds: number;
  firstStart: string | null;
  lastEnd: string | null;
}

export interface ReportTotals {
  workdays: number;
  employees: number;
  productiveSeconds: number;
  breakSeconds: number;
  spanSeconds: number;
  breakCount: number;
}

export function totalsOf(records: readonly WorkdayRecord[]): ReportTotals {
  return {
    workdays: records.length,
    employees: new Set(records.map((r) => r.employee.id)).size,
    productiveSeconds: records.reduce((a, r) => a + r.productiveSeconds, 0),
    breakSeconds: records.reduce((a, r) => a + r.breakSeconds, 0),
    spanSeconds: records.reduce((a, r) => a + r.spanSeconds, 0),
    breakCount: records.reduce((a, r) => a + r.breakCount, 0),
  };
}

export function aggregate(records: readonly WorkdayRecord[], by: Exclude<GroupBy, "workday">): AggregateRow[] {
  const groups = new Map<string, { row: AggregateRow; items: WorkdayRecord[] }>();
  for (const r of records) {
    const key = by === "employee" ? r.employee.id : by === "team" ? (r.team?.id ?? "none") : r.date;
    let g = groups.get(key);
    if (!g) {
      const label =
        by === "employee" ? `${r.employee.firstName} ${r.employee.lastName}` : by === "team" ? (r.team?.name ?? "Unassigned") : r.date;
      g = {
        row: {
          key,
          label,
          sublabel: by === "employee" ? (r.employee.employeeCode ?? undefined) : undefined,
          href: by === "employee" ? { kind: "employee", id: r.employee.id } : by === "team" && r.team ? { kind: "team", id: r.team.id } : undefined,
          workdays: 0,
          productiveSeconds: 0,
          breakSeconds: 0,
          spanSeconds: 0,
          breakCount: 0,
          avgProductiveSeconds: 0,
          firstStart: null,
          lastEnd: null,
        },
        items: [],
      };
      groups.set(key, g);
    }
    g.items.push(r);
    const row = g.row;
    row.workdays++;
    row.productiveSeconds += r.productiveSeconds;
    row.breakSeconds += r.breakSeconds;
    row.spanSeconds += r.spanSeconds;
    row.breakCount += r.breakCount;
    if (!row.firstStart || r.startedAt < row.firstStart) row.firstStart = r.startedAt;
    if (r.endedAt && (!row.lastEnd || r.endedAt > row.lastEnd)) row.lastEnd = r.endedAt;
  }
  const rows = [...groups.values()].map(({ row }) => ({ ...row, avgProductiveSeconds: row.workdays ? Math.round(row.productiveSeconds / row.workdays) : 0 }));
  return by === "date" ? rows.sort((a, b) => b.key.localeCompare(a.key)) : rows.sort((a, b) => a.label.localeCompare(b.label));
}

export interface DailyPoint {
  date: DateKey;
  productiveSeconds: number;
  breakSeconds: number;
  workdays: number;
}

/** One point per calendar day in the range (zero-filled). */
export function dailySeries(records: readonly WorkdayRecord[], from: DateKey, to: DateKey): DailyPoint[] {
  const map = new Map<DateKey, DailyPoint>(eachDay(from, to).map((d) => [d, { date: d, productiveSeconds: 0, breakSeconds: 0, workdays: 0 }]));
  for (const r of records) {
    const p = map.get(r.date);
    if (!p) continue;
    p.productiveSeconds += r.productiveSeconds;
    p.breakSeconds += r.breakSeconds;
    p.workdays++;
  }
  return [...map.values()];
}
