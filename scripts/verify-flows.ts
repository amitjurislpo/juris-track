/**
 * End-to-end verification of the time-tracking engine against the real
 * database. Creates temporary users (…@verify.test), exercises the service
 * layer exactly as the server actions do, and removes everything afterwards.
 *
 *   npm run test:flows
 */
import "dotenv/config";
import assert from "node:assert/strict";
import { db } from "../src/lib/db";
import { AppError } from "../src/lib/errors";
import { computeTotals } from "../src/lib/time/calc";
import type { SessionUser } from "../src/lib/auth/session";
import { getBoard } from "../src/server/services/board.service";
import { setEmployeeActive } from "../src/server/services/employees.service";
import { assertCanViewEmployee } from "../src/server/services/scope.service";
import { adjustSession } from "../src/server/services/time-corrections.service";
import { applyTimeCommand, getEmployeeState } from "../src/server/services/time-tracking.service";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
let passed = 0;
async function check(name: string, fn: () => Promise<void> | void) {
  try {
    await fn();
    passed++;
    console.log(`  ✓ ${name}`);
  } catch (e) {
    console.error(`  ✗ ${name}`);
    throw e;
  }
}
async function rejects(p: Promise<unknown>, code: string) {
  try {
    await p;
  } catch (e) {
    assert.ok(e instanceof AppError, `expected AppError, got ${String(e)}`);
    assert.equal(e.code, code);
    return e;
  }
  assert.fail(`expected rejection with ${code}`);
}

const DOMAIN = "verify.test";
const asActor = (u: { id: string; firstName: string; lastName: string; email: string; role: SessionUser["role"] }): SessionUser => ({
  ...u,
  employeeCode: null,
  mustChangePassword: false,
});

async function cleanup() {
  const users = await db.user.findMany({ where: { email: { endsWith: `@${DOMAIN}` } }, select: { id: true } });
  const ids = users.map((u) => u.id);
  await db.auditLog.deleteMany({ where: { OR: [{ actorId: { in: ids } }, { subjectId: { in: ids } }] } });
  await db.workSession.deleteMany({ where: { employeeId: { in: ids } } });
  await db.breakSession.deleteMany({ where: { employeeId: { in: ids } } });
  await db.workday.deleteMany({ where: { employeeId: { in: ids } } });
  await db.teamMembership.deleteMany({ where: { employeeId: { in: ids } } });
  await db.team.deleteMany({ where: { nameKey: { startsWith: "verify-" } } });
  await db.user.deleteMany({ where: { id: { in: ids } } });
}

async function makeUser(first: string, role: SessionUser["role"] = "EMPLOYEE") {
  return db.user.create({
    data: { firstName: first, lastName: "Verify", email: `${first.toLowerCase()}@${DOMAIN}`, role, passwordHash: "x" },
    select: { id: true, firstName: true, lastName: true, email: true, role: true },
  });
}

async function main() {
  await cleanup();

  console.log("\nCalculation (spec examples)");
  await check("9h span, 1h break, 8h productive from intervals", () => {
    const d = (t: string) => new Date(`2026-10-01T${t}:00Z`);
    const work = [
      { startedAt: d("09:00"), endedAt: d("11:15") },
      { startedAt: d("11:30"), endedAt: d("13:00") },
      { startedAt: d("13:45"), endedAt: d("18:00") },
    ];
    const breaks = [
      { startedAt: d("11:15"), endedAt: d("11:30") },
      { startedAt: d("13:00"), endedAt: d("13:45") },
    ];
    const t = computeTotals({ startedAt: d("09:00"), endedAt: d("18:00") }, work, breaks, d("18:00"));
    assert.deepEqual(t, { productiveSeconds: 8 * 3600, breakSeconds: 3600, spanSeconds: 9 * 3600, breakCount: 2 });
  });
  await check("8.5h productive, 30m break (principle example)", () => {
    const d = (t: string) => new Date(`2026-10-01T${t}:00Z`);
    const t = computeTotals(
      { startedAt: d("09:00"), endedAt: d("18:00") },
      [
        { startedAt: d("09:00"), endedAt: d("11:00") },
        { startedAt: d("11:30"), endedAt: d("18:00") },
      ],
      [{ startedAt: d("11:00"), endedAt: d("11:30") }],
      d("18:00"),
    );
    assert.equal(t.productiveSeconds, 8.5 * 3600);
    assert.equal(t.breakSeconds, 1800);
  });

  const admin = await makeUser("Admin", "ADMIN");
  const managerA = await makeUser("ManagerA", "MANAGER");
  const managerB = await makeUser("ManagerB", "MANAGER");
  const a = await makeUser("Alice");
  const b = await makeUser("Bob");
  const c = await makeUser("Carol");
  const teamA = await db.team.create({ data: { name: "Verify A", nameKey: "verify-a", managerId: managerA.id } });
  const teamB = await db.team.create({ data: { name: "Verify B", nameKey: "verify-b", managerId: managerB.id } });
  for (const [u, t] of [[a, teamA], [b, teamA], [c, teamB]] as const) {
    await db.teamMembership.create({ data: { teamId: t.id, employeeId: u.id, activeEmployeeId: u.id } });
  }

  console.log("\nWorkday flow");
  await check("invalid transitions before starting are rejected", async () => {
    await rejects(applyTimeCommand(a.id, "break"), "INVALID_STATE");
    await rejects(applyTimeCommand(a.id, "resume"), "INVALID_STATE");
    await rejects(applyTimeCommand(a.id, "end"), "INVALID_STATE");
    assert.equal((await getEmployeeState(a.id)).status, "NOT_STARTED");
  });

  await check("start → WORKING with one open work session", async () => {
    const r = await applyTimeCommand(a.id, "start");
    assert.equal(r.state.status, "WORKING");
    assert.equal(r.alreadyApplied, false);
    assert.equal(r.state.workday!.segments.length, 1);
  });

  await sleep(1200);
  await check("break → ON_BREAK; productive time frozen while on break", async () => {
    const r = await applyTimeCommand(a.id, "break");
    assert.equal(r.state.status, "ON_BREAK");
    const frozen = r.state.workday!.productiveSeconds;
    assert.ok(frozen >= 1, "work time recorded before the break");
    await sleep(1500);
    const later = await getEmployeeState(a.id);
    assert.equal(later.workday!.productiveSeconds, frozen, "productive must not grow during a break");
    assert.ok(later.workday!.breakSeconds >= 1, "break time grows");
  });

  await check("end while on break is rejected (policy)", async () => {
    await rejects(applyTimeCommand(a.id, "end"), "INVALID_STATE");
  });

  await check("resume → WORKING with a new work session", async () => {
    const r = await applyTimeCommand(a.id, "resume");
    assert.equal(r.state.status, "WORKING");
    assert.deepEqual(r.state.workday!.segments.map((s) => s.kind), ["WORK", "BREAK", "WORK"]);
  });

  await sleep(1100);
  await check("second break and resume are recorded individually", async () => {
    await applyTimeCommand(a.id, "break");
    await sleep(500);
    const r = await applyTimeCommand(a.id, "resume");
    assert.equal(r.state.workday!.breakCount, 2);
  });

  await check("end → DAY_ENDED; stored totals equal the sum of intervals", async () => {
    const r = await applyTimeCommand(a.id, "end");
    assert.equal(r.state.status, "DAY_ENDED");
    const wd = await db.workday.findUniqueOrThrow({ where: { id: r.state.workday!.id }, include: { workSessions: true, breakSessions: true } });
    const sum = (xs: { startedAt: Date; endedAt: Date | null }[]) => xs.reduce((s, x) => s + Math.floor((x.endedAt!.getTime() - x.startedAt.getTime()) / 1000), 0);
    assert.equal(wd.status, "COMPLETED");
    assert.equal(wd.activeEmployeeId, null);
    assert.equal(wd.productiveSeconds, sum(wd.workSessions));
    assert.equal(wd.breakSeconds, sum(wd.breakSessions));
    assert.equal(wd.breakCount, 2);
    assert.ok(wd.workSessions.every((s) => s.endedAt) && wd.breakSessions.every((s) => s.endedAt));
  });

  await check("end twice is idempotent; starting again the same day is rejected", async () => {
    const again = await applyTimeCommand(a.id, "end");
    assert.equal(again.alreadyApplied, true);
    await rejects(applyTimeCommand(a.id, "start"), "INVALID_STATE");
    assert.equal(await db.workday.count({ where: { employeeId: a.id } }), 1);
  });

  await check("state is identical when read again (refresh / another device)", async () => {
    const s1 = await getEmployeeState(a.id);
    const s2 = await getEmployeeState(a.id);
    assert.equal(s1.workday!.productiveSeconds, s2.workday!.productiveSeconds);
    assert.equal(s1.workday!.id, s2.workday!.id);
  });

  console.log("\nConcurrency (double clicks / parallel requests)");
  await check("10 parallel starts create exactly one workday and one open session", async () => {
    const results = await Promise.all(Array.from({ length: 10 }, () => applyTimeCommand(b.id, "start")));
    assert.equal(results.filter((r) => !r.alreadyApplied).length, 1);
    assert.equal(await db.workday.count({ where: { employeeId: b.id } }), 1);
    assert.equal(await db.workSession.count({ where: { employeeId: b.id, endedAt: null } }), 1);
  });
  await check("10 parallel breaks create exactly one break", async () => {
    await Promise.all(Array.from({ length: 10 }, () => applyTimeCommand(b.id, "break")));
    assert.equal(await db.breakSession.count({ where: { employeeId: b.id } }), 1);
    assert.equal(await db.workSession.count({ where: { employeeId: b.id, endedAt: null } }), 0);
  });
  await check("10 parallel resumes create exactly one new work session", async () => {
    await Promise.all(Array.from({ length: 10 }, () => applyTimeCommand(b.id, "resume")));
    assert.equal(await db.workSession.count({ where: { employeeId: b.id } }), 2);
    assert.equal(await db.breakSession.count({ where: { employeeId: b.id, endedAt: null } }), 0);
  });
  await check("mixed parallel break+resume never leaves both or neither interval open", async () => {
    await Promise.all(Array.from({ length: 12 }, (_, i) => applyTimeCommand(b.id, i % 2 ? "break" : "resume")));
    const openWork = await db.workSession.count({ where: { employeeId: b.id, endedAt: null } });
    const openBreak = await db.breakSession.count({ where: { employeeId: b.id, endedAt: null } });
    assert.equal(openWork + openBreak, 1);
    const wd = await db.workday.findFirstOrThrow({ where: { employeeId: b.id } });
    assert.equal(wd.status, openWork ? "WORKING" : "ON_BREAK");
  });

  console.log("\nIndependent timers");
  await check("changing Bob's state never touches Carol's or Alice's records", async () => {
    await applyTimeCommand(c.id, "start");
    const before = { a: await getEmployeeState(a.id), c: await db.workSession.findMany({ where: { employeeId: c.id } }) };
    const bNow = await getEmployeeState(b.id);
    if (bNow.status === "ON_BREAK") await applyTimeCommand(b.id, "resume");
    await applyTimeCommand(b.id, "break");
    await applyTimeCommand(b.id, "resume");
    await applyTimeCommand(b.id, "end");
    const afterC = await db.workSession.findMany({ where: { employeeId: c.id } });
    assert.deepEqual(afterC, before.c);
    assert.equal((await getEmployeeState(c.id)).status, "WORKING");
    assert.equal((await getEmployeeState(a.id)).workday!.productiveSeconds, before.a.workday!.productiveSeconds);
    assert.equal((await getEmployeeState(b.id)).status, "DAY_ENDED");
  });

  console.log("\nAuthorization scope");
  await check("manager A sees only team A on the board", async () => {
    const board = await getBoard(asActor(managerA));
    const ids = board.rows.map((r) => r.employee.id).sort();
    assert.deepEqual(ids, [a.id, b.id].sort());
  });
  await check("manager A cannot view an employee of team B", async () => {
    await rejects(assertCanViewEmployee(asActor(managerA), c.id), "NOT_FOUND");
    await assertCanViewEmployee(asActor(managerB), c.id);
  });
  await check("employees can only view themselves", async () => {
    await rejects(assertCanViewEmployee(asActor(a), b.id), "NOT_FOUND");
    await assertCanViewEmployee(asActor(a), a.id);
  });
  await check("non-admins cannot adjust time records", async () => {
    const s = await db.workSession.findFirstOrThrow({ where: { employeeId: a.id } });
    await rejects(adjustSession(asActor(managerA), { kind: "WORK", sessionId: s.id, startedAt: s.startedAt, endedAt: s.endedAt, reason: "attempt" }), "FORBIDDEN");
  });

  console.log("\nAdmin corrections (audited)");
  await check("overlapping adjustment is rejected", async () => {
    const [first] = await db.workSession.findMany({ where: { employeeId: a.id }, orderBy: { startedAt: "asc" } });
    const brk = await db.breakSession.findFirstOrThrow({ where: { employeeId: a.id }, orderBy: { startedAt: "asc" } });
    await rejects(
      adjustSession(asActor(admin), { kind: "WORK", sessionId: first!.id, startedAt: first!.startedAt, endedAt: new Date(brk.endedAt!.getTime() + 1000), reason: "overlap test" }),
      "VALIDATION",
    );
  });
  await check("valid adjustment recomputes totals and writes an audit entry", async () => {
    const [first] = await db.workSession.findMany({ where: { employeeId: a.id }, orderBy: { startedAt: "asc" } });
    const wdBefore = await db.workday.findUniqueOrThrow({ where: { id: first!.workdayId } });
    const newStart = new Date(first!.startedAt.getTime() - 60 * 60 * 1000); // one hour earlier
    await adjustSession(asActor(admin), { kind: "WORK", sessionId: first!.id, startedAt: newStart, endedAt: first!.endedAt, reason: "Employee started early; confirmed by manager" });
    const wdAfter = await db.workday.findUniqueOrThrow({ where: { id: first!.workdayId } });
    assert.equal(wdAfter.productiveSeconds! - wdBefore.productiveSeconds!, 3600);
    assert.equal(wdAfter.startedAt.getTime(), newStart.getTime());
    const audit = await db.auditLog.findFirstOrThrow({ where: { action: "TIME_ADJUSTED", entityId: first!.id } });
    assert.equal(audit.actorId, admin.id);
    assert.equal(audit.reason, "Employee started early; confirmed by manager");
    assert.equal((audit.before as { startedAt: string }).startedAt, first!.startedAt.toISOString());
  });

  console.log("\nDeactivation");
  await check("deactivating an employee closes their open workday and blocks time commands", async () => {
    await setEmployeeActive(asActor(admin), c.id, false, "Verification: left the organization");
    const wd = await db.workday.findFirstOrThrow({ where: { employeeId: c.id } });
    assert.equal(wd.status, "COMPLETED");
    assert.equal(await db.workSession.count({ where: { employeeId: c.id, endedAt: null } }), 0);
    await rejects(applyTimeCommand(c.id, "start"), "FORBIDDEN");
    assert.ok(await db.auditLog.findFirst({ where: { action: "WORKDAY_CLOSED_BY_ADMIN", subjectId: c.id } }));
  });

  await cleanup();
  console.log(`\nAll ${passed} checks passed.`);
}

main()
  .catch(async (e) => {
    console.error(e);
    await cleanup().catch(() => undefined);
    process.exitCode = 1;
  })
  .finally(() => db.$disconnect());
