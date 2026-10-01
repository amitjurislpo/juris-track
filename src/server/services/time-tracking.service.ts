import { Prisma } from "@/generated/prisma/client";
import { db, type Tx } from "@/lib/db";
import { AppError } from "@/lib/errors";
import { closedSeconds, computeTotals, openInterval } from "@/lib/time/calc";
import { dateKeyToDb, dbToDateKey, toDateKey } from "@/lib/time/tz";
import type {
  ActivityKind,
  EmployeeTimeState,
  LiveStatus,
  TimeCommand,
  TimeCommandResult,
  TimelineSegment,
  WorkdaySnapshot,
} from "@/lib/time/types";
import { getSettings } from "./settings.service";

/**
 * Time-tracking state machine.
 *
 *   NOT_STARTED --start--> WORKING --break--> ON_BREAK --resume--> WORKING
 *                          WORKING --end----> DAY_ENDED --start--> WORKING (same day)
 *
 * Every transition:
 *  - runs in a transaction that first takes a row lock on the employee
 *    (SELECT ... FOR UPDATE), so concurrent requests for the same employee
 *    are serialized while other employees are never blocked;
 *  - reads the current state inside the lock and validates the transition;
 *  - is idempotent when the employee is already in the target state (a
 *    double-click returns the current state and creates nothing);
 *  - uses the database clock, so every app instance agrees on "now".
 * Unique "open key" columns make duplicate open records impossible even if
 * this code were bypassed.
 */

export const workdayWithSessions = {
  workSessions: { orderBy: { startedAt: "asc" } },
  breakSessions: { orderBy: { startedAt: "asc" } },
} satisfies Prisma.WorkdayInclude;

export type WorkdayWithSessions = Prisma.WorkdayGetPayload<{ include: typeof workdayWithSessions }>;

// ─── Snapshot building ───────────────────────────────────────────────────────

export function liveStatusOf(status: WorkdayWithSessions["status"]): LiveStatus {
  return status === "COMPLETED" ? "DAY_ENDED" : status;
}

export function buildSegments(wd: WorkdayWithSessions): TimelineSegment[] {
  const iso = (d: Date | null) => (d ? d.toISOString() : null);
  return [
    ...wd.workSessions.map((s) => ({ id: s.id, kind: "WORK" as const, startedAt: s.startedAt.toISOString(), endedAt: iso(s.endedAt) })),
    ...wd.breakSessions.map((s) => ({ id: s.id, kind: "BREAK" as const, startedAt: s.startedAt.toISOString(), endedAt: iso(s.endedAt) })),
  ].sort((a, b) => a.startedAt.localeCompare(b.startedAt));
}

function lastActivityOf(wd: WorkdayWithSessions, segments: TimelineSegment[]): { kind: ActivityKind; at: string } {
  if (wd.endedAt) return { kind: "ENDED_DAY", at: wd.endedAt.toISOString() };
  const last = segments[segments.length - 1];
  if (!last) return { kind: "STARTED_WORK", at: wd.startedAt.toISOString() };
  if (last.kind === "BREAK") return { kind: "STARTED_BREAK", at: last.startedAt };
  return { kind: segments.length === 1 ? "STARTED_WORK" : "RESUMED_WORK", at: last.startedAt };
}

export function buildSnapshot(
  wd: WorkdayWithSessions,
  ctx: { now: Date; today: string; staleWorkdayHours: number },
): WorkdaySnapshot {
  const totals = computeTotals(wd, wd.workSessions, wd.breakSessions, ctx.now);
  const segments = buildSegments(wd);
  const openWork = openInterval(wd.workSessions);
  const openBreak = openInterval(wd.breakSessions);
  const date = dbToDateKey(wd.date);
  const isOpen = wd.status !== "COMPLETED";
  return {
    id: wd.id,
    date,
    status: liveStatusOf(wd.status),
    startedAt: wd.startedAt.toISOString(),
    endedAt: wd.endedAt?.toISOString() ?? null,
    // Duration-based only: crossing midnight is normal for night shifts.
    isStale: isOpen && totals.spanSeconds > ctx.staleWorkdayHours * 3600,
    closedProductiveSeconds: closedSeconds(wd.workSessions),
    closedBreakSeconds: closedSeconds(wd.breakSessions),
    activeWorkStartedAt: openWork?.startedAt.toISOString() ?? null,
    activeBreakStartedAt: openBreak?.startedAt.toISOString() ?? null,
    ...totals,
    lastActivity: lastActivityOf(wd, segments),
    segments,
  };
}

// ─── Reads ───────────────────────────────────────────────────────────────────

/**
 * Current state for one employee: their open workday if any (even one left
 * open from a previous date), otherwise today's completed workday, otherwise
 * NOT_STARTED.
 */
export async function getEmployeeState(employeeId: string): Promise<EmployeeTimeState> {
  const settings = await getSettings();
  const now = new Date();
  const today = toDateKey(now, settings.timezone);

  const workday =
    (await db.workday.findUnique({ where: { activeEmployeeId: employeeId }, include: workdayWithSessions })) ??
    (await db.workday.findUnique({
      where: { employeeId_date: { employeeId, date: dateKeyToDb(today) } },
      include: workdayWithSessions,
    }));

  const snapshot = workday ? buildSnapshot(workday, { now, today, staleWorkdayHours: settings.staleWorkdayHours }) : null;
  return {
    employeeId,
    serverNow: now.toISOString(),
    today,
    timezone: settings.timezone,
    status: snapshot?.status ?? "NOT_STARTED",
    workday: snapshot,
  };
}

// ─── Transitions ─────────────────────────────────────────────────────────────

interface LockedContext {
  tx: Tx;
  now: Date;
}

/** Runs fn in a transaction holding a row lock on the employee. */
export async function withEmployeeLock<T>(employeeId: string, fn: (ctx: LockedContext) => Promise<T>): Promise<T> {
  return db.$transaction(
    async (tx) => {
      const rows = await tx.$queryRaw<{ id: string; isActive: boolean; now: Date }[]>`
        SELECT "id", "isActive", clock_timestamp() AS "now" FROM "User" WHERE "id" = ${employeeId} FOR UPDATE`;
      const row = rows[0];
      if (!row) throw new AppError("NOT_FOUND", "Employee not found.");
      if (!row.isActive) throw new AppError("FORBIDDEN", "This account is deactivated.");
      return fn({ tx, now: new Date(row.now) });
    },
    { isolationLevel: Prisma.TransactionIsolationLevel.ReadCommitted, maxWait: 5_000, timeout: 10_000 },
  );
}

function openWorkday(tx: Tx, employeeId: string) {
  return tx.workday.findUnique({ where: { activeEmployeeId: employeeId }, include: workdayWithSessions });
}

/** Asserts the invariants that must hold for an open workday before we mutate it. */
function assertConsistent(wd: WorkdayWithSessions) {
  const openWork = openInterval(wd.workSessions);
  const openBreak = openInterval(wd.breakSessions);
  const ok = wd.status === "WORKING" ? openWork && !openBreak : wd.status === "ON_BREAK" ? openBreak && !openWork : false;
  if (!ok) {
    throw new AppError(
      "INTERNAL",
      "Your time record is in an unexpected state. Please contact an administrator to review it.",
    );
  }
  return { openWork, openBreak };
}

/** Recomputes the cached totals from sessions (never from counters). */
export async function recomputeWorkdayTotals(tx: Tx, workdayId: string) {
  const wd = await tx.workday.findUniqueOrThrow({ where: { id: workdayId }, include: workdayWithSessions });
  if (wd.status !== "COMPLETED" || !wd.endedAt) {
    await tx.workday.update({ where: { id: workdayId }, data: { productiveSeconds: null, breakSeconds: null, breakCount: null } });
    return;
  }
  const totals = computeTotals(wd, wd.workSessions, wd.breakSessions, wd.endedAt);
  await tx.workday.update({
    where: { id: workdayId },
    data: { productiveSeconds: totals.productiveSeconds, breakSeconds: totals.breakSeconds, breakCount: totals.breakCount },
  });
}

async function start(employeeId: string): Promise<boolean> {
  const settings = await getSettings();
  return withEmployeeLock(employeeId, async ({ tx, now }) => {
    if (await openWorkday(tx, employeeId)) return true; // already working/on break

    const date = toDateKey(now, settings.timezone);
    const existing = await tx.workday.findUnique({
      where: { employeeId_date: { employeeId, date: dateKeyToDb(date) } },
      select: { id: true },
    });
    if (existing) {
      // Today's workday was already ended: reopen it with a new work session.
      // The gap since it ended counts as neither productive nor break time.
      await tx.workday.update({
        where: { id: existing.id },
        data: { status: "WORKING", endedAt: null, activeEmployeeId: employeeId },
      });
      await tx.workSession.create({
        data: { employeeId, workdayId: existing.id, startedAt: now, openWorkdayId: existing.id },
      });
      await recomputeWorkdayTotals(tx, existing.id);
      return false;
    }

    const membership = await tx.teamMembership.findUnique({
      where: { activeEmployeeId: employeeId },
      select: { teamId: true },
    });

    const workday = await tx.workday.create({
      data: {
        employeeId,
        teamId: membership?.teamId ?? null,
        date: dateKeyToDb(date),
        timezone: settings.timezone,
        startedAt: now,
        status: "WORKING",
        activeEmployeeId: employeeId,
      },
    });
    await tx.workSession.create({
      data: { employeeId, workdayId: workday.id, startedAt: now, openWorkdayId: workday.id },
    });
    return false;
  });
}

async function takeBreak(employeeId: string): Promise<boolean> {
  return withEmployeeLock(employeeId, async ({ tx, now }) => {
    const wd = await openWorkday(tx, employeeId);
    if (!wd) throw new AppError("INVALID_STATE", "Start your workday before taking a break.");
    if (wd.status === "ON_BREAK") return true;
    const { openWork } = assertConsistent(wd);

    await tx.workSession.update({ where: { id: openWork!.id }, data: { endedAt: now, openWorkdayId: null } });
    await tx.breakSession.create({ data: { employeeId, workdayId: wd.id, startedAt: now, openWorkdayId: wd.id } });
    await tx.workday.update({ where: { id: wd.id }, data: { status: "ON_BREAK" } });
    return false;
  });
}

async function resume(employeeId: string): Promise<boolean> {
  return withEmployeeLock(employeeId, async ({ tx, now }) => {
    const wd = await openWorkday(tx, employeeId);
    if (!wd) throw new AppError("INVALID_STATE", "You are not on a break.");
    if (wd.status === "WORKING") return true;
    const { openBreak } = assertConsistent(wd);

    await tx.breakSession.update({ where: { id: openBreak!.id }, data: { endedAt: now, openWorkdayId: null } });
    await tx.workSession.create({ data: { employeeId, workdayId: wd.id, startedAt: now, openWorkdayId: wd.id } });
    await tx.workday.update({ where: { id: wd.id }, data: { status: "WORKING" } });
    return false;
  });
}

async function end(employeeId: string): Promise<boolean> {
  const settings = await getSettings();
  return withEmployeeLock(employeeId, async ({ tx, now }) => {
    const wd = await openWorkday(tx, employeeId);
    if (!wd) {
      const today = toDateKey(now, settings.timezone);
      const done = await tx.workday.findUnique({
        where: { employeeId_date: { employeeId, date: dateKeyToDb(today) } },
        select: { status: true },
      });
      if (done?.status === "COMPLETED") return true;
      throw new AppError("INVALID_STATE", "You have not started a workday.");
    }
    if (wd.status === "ON_BREAK") {
      // Policy: a break must be closed explicitly so its end time is real.
      throw new AppError("INVALID_STATE", "You are on a break. Select Back to Work before ending your workday.");
    }
    const { openWork } = assertConsistent(wd);

    await tx.workSession.update({ where: { id: openWork!.id }, data: { endedAt: now, openWorkdayId: null } });
    await tx.workday.update({
      where: { id: wd.id },
      data: { status: "COMPLETED", endedAt: now, activeEmployeeId: null },
    });
    await recomputeWorkdayTotals(tx, wd.id);
    return false;
  });
}

const COMMANDS: Record<TimeCommand, (employeeId: string) => Promise<boolean>> = {
  start,
  break: takeBreak,
  resume,
  end,
};

/**
 * Applies a time command for the given employee. The employee id always
 * comes from the authenticated session, never from the client.
 */
export async function applyTimeCommand(employeeId: string, command: TimeCommand): Promise<TimeCommandResult> {
  let alreadyApplied: boolean;
  try {
    alreadyApplied = await COMMANDS[command](employeeId);
  } catch (e) {
    // A unique-key collision means a concurrent request already opened the
    // record; report the resulting state instead of failing.
    if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === "P2002") alreadyApplied = true;
    else throw e;
  }
  return { state: await getEmployeeState(employeeId), alreadyApplied };
}
