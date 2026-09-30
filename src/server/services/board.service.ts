import type { Prisma } from "@/generated/prisma/client";
import { db } from "@/lib/db";
import type { SessionUser } from "@/lib/auth/session";
import { TRACKED_ROLES } from "@/lib/auth/roles";
import { dateKeyToDb, toDateKey, type DateKey } from "@/lib/time/tz";
import type { LiveStatus, WorkdaySnapshot } from "@/lib/time/types";
import { getSettings } from "./settings.service";
import { employeeScopeWhere } from "./scope.service";
import { buildSnapshot, workdayWithSessions, type WorkdayWithSessions } from "./time-tracking.service";

export type WorkdaySummary = Omit<WorkdaySnapshot, "segments">;

export interface BoardRow {
  employee: { id: string; firstName: string; lastName: string; email: string; employeeCode: string | null };
  team: { id: string; name: string } | null;
  status: LiveStatus;
  workday: WorkdaySummary | null;
}

export interface BoardMetrics {
  total: number;
  working: number;
  onBreak: number;
  notStarted: number;
  dayEnded: number;
  stale: number;
  productiveSeconds: number;
  breakSeconds: number;
}

export interface BoardData {
  date: DateKey;
  today: DateKey;
  isToday: boolean;
  timezone: string;
  serverNow: string;
  rows: BoardRow[];
  metrics: BoardMetrics;
}

export interface BoardFilters {
  date?: DateKey;
  teamId?: string;
  employeeId?: string;
  status?: LiveStatus;
}

export function emptyMetrics(): BoardMetrics {
  return { total: 0, working: 0, onBreak: 0, notStarted: 0, dayEnded: 0, stale: 0, productiveSeconds: 0, breakSeconds: 0 };
}

export function metricsOf(rows: readonly BoardRow[]): BoardMetrics {
  const m = emptyMetrics();
  for (const r of rows) {
    m.total++;
    if (r.status === "WORKING") m.working++;
    else if (r.status === "ON_BREAK") m.onBreak++;
    else if (r.status === "DAY_ENDED") m.dayEnded++;
    else m.notStarted++;
    if (r.workday?.isStale) m.stale++;
    m.productiveSeconds += r.workday?.productiveSeconds ?? 0;
    m.breakSeconds += r.workday?.breakSeconds ?? 0;
  }
  return m;
}

function stripSegments(s: WorkdaySnapshot): WorkdaySummary {
  const { segments: _segments, ...rest } = s;
  return rest;
}

/**
 * Status board for every time-tracked, active employee in the actor's scope.
 * For today, an employee's open workday is shown even if it began on an
 * earlier date (e.g. a night shift across midnight); for other dates, that
 * date's workday.
 */
export async function getBoard(actor: SessionUser, filters: BoardFilters = {}): Promise<BoardData> {
  const settings = await getSettings();
  const now = new Date();
  const today = toDateKey(now, settings.timezone);
  const date = filters.date && filters.date <= today ? filters.date : today;
  const isToday = date === today;

  const where: Prisma.UserWhereInput = {
    AND: [
      employeeScopeWhere(actor),
      { isActive: true, role: { in: [...TRACKED_ROLES] } },
      filters.teamId ? { memberships: { some: { endedAt: null, teamId: filters.teamId } } } : {},
      filters.employeeId ? { id: filters.employeeId } : {},
    ],
  };

  const employees = await db.user.findMany({
    where,
    orderBy: [{ firstName: "asc" }, { lastName: "asc" }],
    select: {
      id: true,
      firstName: true,
      lastName: true,
      email: true,
      employeeCode: true,
      memberships: { where: { endedAt: null }, select: { team: { select: { id: true, name: true } } }, take: 1 },
    },
  });
  const ids = employees.map((e) => e.id);

  const workdays: WorkdayWithSessions[] = ids.length
    ? await db.workday.findMany({
        where: {
          employeeId: { in: ids },
          OR: isToday ? [{ date: dateKeyToDb(date) }, { activeEmployeeId: { not: null } }] : [{ date: dateKeyToDb(date) }],
        },
        include: workdayWithSessions,
      })
    : [];

  // Prefer the open workday for today's view; otherwise the date's record.
  const byEmployee = new Map<string, WorkdayWithSessions>();
  for (const wd of workdays) {
    const existing = byEmployee.get(wd.employeeId);
    if (!existing || (isToday && wd.status !== "COMPLETED")) byEmployee.set(wd.employeeId, wd);
  }

  const ctx = { now, today, staleWorkdayHours: settings.staleWorkdayHours };
  const allRows: BoardRow[] = employees.map((e) => {
    const wd = byEmployee.get(e.id);
    const snapshot = wd ? stripSegments(buildSnapshot(wd, ctx)) : null;
    return {
      employee: { id: e.id, firstName: e.firstName, lastName: e.lastName, email: e.email, employeeCode: e.employeeCode },
      team: e.memberships[0]?.team ?? null,
      status: snapshot?.status ?? "NOT_STARTED",
      workday: snapshot,
    };
  });

  const rows = filters.status ? allRows.filter((r) => r.status === filters.status) : allRows;
  return { date, today, isToday, timezone: settings.timezone, serverNow: now.toISOString(), rows, metrics: metricsOf(allRows) };
}

/** Aggregated live metrics per team id (for team lists and detail pages). */
export function metricsByTeam(rows: readonly BoardRow[]): Map<string, BoardMetrics> {
  const grouped = new Map<string, BoardRow[]>();
  for (const r of rows) {
    if (!r.team) continue;
    const list = grouped.get(r.team.id) ?? [];
    list.push(r);
    grouped.set(r.team.id, list);
  }
  return new Map([...grouped].map(([id, list]) => [id, metricsOf(list)]));
}
