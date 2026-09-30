import { db, type Tx } from "@/lib/db";
import { AppError } from "@/lib/errors";
import type { SessionUser } from "@/lib/auth/session";
import { recordAudit } from "./audit.service";
import { recomputeWorkdayTotals, withEmployeeLock, workdayWithSessions } from "./time-tracking.service";

/**
 * Administrator-only corrections. Original records are adjusted in place
 * but every change is written to the audit log with the previous value, the
 * new value, who made it, when, and the mandatory reason.
 */

interface Interval {
  id: string;
  kind: "WORK" | "BREAK";
  startedAt: Date;
  endedAt: Date | null;
}

function assertNoOverlap(intervals: Interval[], now: Date) {
  const sorted = [...intervals].sort((a, b) => a.startedAt.getTime() - b.startedAt.getTime());
  for (let i = 0; i < sorted.length; i++) {
    const cur = sorted[i]!;
    const curEnd = cur.endedAt ?? now;
    if (curEnd < cur.startedAt) throw new AppError("VALIDATION", "An interval cannot end before it starts.");
    if (cur.startedAt > now || curEnd > now) throw new AppError("VALIDATION", "Times cannot be in the future.");
    const next = sorted[i + 1];
    if (next && next.startedAt < curEnd) {
      throw new AppError("VALIDATION", "The adjusted interval overlaps another work or break interval.");
    }
    if (!cur.endedAt && next) throw new AppError("VALIDATION", "Only the latest interval may remain open.");
  }
}

export interface AdjustSessionInput {
  kind: "WORK" | "BREAK";
  sessionId: string;
  startedAt: Date;
  endedAt: Date | null;
  reason: string;
}

export async function adjustSession(actor: SessionUser, input: AdjustSessionInput): Promise<void> {
  if (actor.role !== "ADMIN") throw new AppError("FORBIDDEN", "Only administrators can adjust time records.");

  const session =
    input.kind === "WORK"
      ? await db.workSession.findUnique({ where: { id: input.sessionId } })
      : await db.breakSession.findUnique({ where: { id: input.sessionId } });
  if (!session) throw new AppError("NOT_FOUND", "Time record not found.");

  await withEmployeeLock(session.employeeId, async ({ tx, now }) => {
    const wd = await tx.workday.findUniqueOrThrow({ where: { id: session.workdayId }, include: workdayWithSessions });
    const target =
      input.kind === "WORK" ? wd.workSessions.find((s) => s.id === session.id) : wd.breakSessions.find((s) => s.id === session.id);
    if (!target) throw new AppError("NOT_FOUND", "Time record not found.");

    // An open interval stays open (only its start may move); a closed one must stay closed.
    if (!target.endedAt && input.endedAt) {
      throw new AppError("VALIDATION", "This interval is still running. Close the workday instead of setting an end time.");
    }
    if (target.endedAt && !input.endedAt) throw new AppError("VALIDATION", "An end time is required.");

    const all: Interval[] = [
      ...wd.workSessions.map((s) => ({ id: s.id, kind: "WORK" as const, startedAt: s.startedAt, endedAt: s.endedAt })),
      ...wd.breakSessions.map((s) => ({ id: s.id, kind: "BREAK" as const, startedAt: s.startedAt, endedAt: s.endedAt })),
    ].map((i) => (i.id === target.id ? { ...i, startedAt: input.startedAt, endedAt: input.endedAt } : i));
    assertNoOverlap(all, now);

    const sorted = [...all].sort((a, b) => a.startedAt.getTime() - b.startedAt.getTime());
    if (sorted[0]!.kind !== "WORK") throw new AppError("VALIDATION", "A workday must begin with a work interval.");

    const data = { startedAt: input.startedAt, endedAt: input.endedAt };
    if (input.kind === "WORK") await tx.workSession.update({ where: { id: target.id }, data });
    else await tx.breakSession.update({ where: { id: target.id }, data });

    // Keep the workday boundaries aligned with its intervals.
    const first = sorted[0]!.startedAt;
    const lastEnd = wd.status === "COMPLETED" ? sorted[sorted.length - 1]!.endedAt : null;
    await tx.workday.update({ where: { id: wd.id }, data: { startedAt: first, ...(lastEnd ? { endedAt: lastEnd } : {}) } });
    await recomputeWorkdayTotals(tx, wd.id);

    await recordAudit(tx, {
      actorId: actor.id,
      subjectId: session.employeeId,
      action: "TIME_ADJUSTED",
      entityType: input.kind === "WORK" ? "WorkSession" : "BreakSession",
      entityId: target.id,
      before: { startedAt: target.startedAt.toISOString(), endedAt: target.endedAt?.toISOString() ?? null },
      after: { startedAt: input.startedAt.toISOString(), endedAt: input.endedAt?.toISOString() ?? null },
      reason: input.reason,
    });
  });
}

/**
 * Closes an open workday (e.g. an employee forgot to end their day, or is
 * being deactivated). The open work or break interval is ended at `endAt`.
 */
export async function closeOpenWorkday(
  actor: SessionUser,
  workdayId: string,
  endAt: Date | null,
  reason: string,
  opts: { allowInactive?: boolean } = {},
): Promise<void> {
  if (actor.role !== "ADMIN") throw new AppError("FORBIDDEN", "Only administrators can close workdays.");
  const wd0 = await db.workday.findUnique({ where: { id: workdayId }, select: { employeeId: true } });
  if (!wd0) throw new AppError("NOT_FOUND", "Workday not found.");

  const run = async (tx: Tx, now: Date) => {
    const wd = await tx.workday.findUniqueOrThrow({ where: { id: workdayId }, include: workdayWithSessions });
    if (wd.status === "COMPLETED") throw new AppError("INVALID_STATE", "This workday is already completed.");
    const openWork = wd.workSessions.find((s) => !s.endedAt);
    const openBreak = wd.breakSessions.find((s) => !s.endedAt);
    const open = openWork ?? openBreak;
    const end = endAt ?? now;
    if (end > now) throw new AppError("VALIDATION", "The end time cannot be in the future.");
    if (open && end < open.startedAt) {
      throw new AppError("VALIDATION", "The end time must be after the current interval started.");
    }

    if (openWork) await tx.workSession.update({ where: { id: openWork.id }, data: { endedAt: end, openWorkdayId: null } });
    if (openBreak) await tx.breakSession.update({ where: { id: openBreak.id }, data: { endedAt: end, openWorkdayId: null } });
    await tx.workday.update({ where: { id: wd.id }, data: { status: "COMPLETED", endedAt: end, activeEmployeeId: null } });
    await recomputeWorkdayTotals(tx, wd.id);

    await recordAudit(tx, {
      actorId: actor.id,
      subjectId: wd.employeeId,
      action: "WORKDAY_CLOSED_BY_ADMIN",
      entityType: "Workday",
      entityId: wd.id,
      before: { status: wd.status, endedAt: null },
      after: { status: "COMPLETED", endedAt: end.toISOString() },
      reason,
    });
  };

  if (opts.allowInactive) {
    // Used during deactivation, where the employee row may already be inactive.
    await db.$transaction(async (tx) => {
      const rows = await tx.$queryRaw<{ now: Date }[]>`
        SELECT clock_timestamp() AS "now" FROM "User" WHERE "id" = ${wd0.employeeId} FOR UPDATE`;
      await run(tx, new Date(rows[0]!.now));
    });
  } else {
    await withEmployeeLock(wd0.employeeId, ({ tx, now }) => run(tx, now));
  }
}
