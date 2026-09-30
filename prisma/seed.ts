/**
 * Development seed — creates DEMO data only.
 *
 * Every seeded account is flagged `isDemo` and uses the reserved `.test`
 * domain. Teams are inserted as ordinary database rows; nothing in the
 * application depends on these names.
 *
 * This script WIPES existing data. It refuses to run when NODE_ENV is
 * "production" unless SEED_ALLOW_PRODUCTION=true is set explicitly.
 */
import "dotenv/config";
import { db } from "../src/lib/db";
import { hashPassword } from "../src/lib/auth/password";
import { computeTotals } from "../src/lib/time/calc";
import { addDays, dateKeyToDb, toDateKey, zonedTimeToUtc } from "../src/lib/time/tz";

if (process.env.NODE_ENV === "production" && process.env.SEED_ALLOW_PRODUCTION !== "true") {
  console.error("Refusing to seed demo data in production. Set SEED_ALLOW_PRODUCTION=true to override.");
  process.exit(1);
}

const TZ = process.env.ORG_TIMEZONE || "Asia/Kolkata";
const PASSWORD = process.env.SEED_DEMO_PASSWORD || "Demo@12345";
const DOMAIN = "demo.jurislpo.test";
const HISTORY_DAYS = 28;

// Deterministic PRNG so demo data is stable between runs.
function mulberry32(seed: number) {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(20261001);
const between = (min: number, max: number) => Math.round(min + rand() * (max - min));
const MIN = 60_000;

type Scenario = "WORKING" | "ON_BREAK" | "DAY_ENDED" | "NOT_STARTED" | "STALE";

interface Person {
  first: string;
  last: string;
  code: string;
  team: "Paralegal" | "Sales" | "Technology";
  today: Scenario;
}

const PEOPLE: Person[] = [
  { first: "Priya", last: "Sharma", code: "JL-1001", team: "Paralegal", today: "WORKING" },
  { first: "Rahul", last: "Verma", code: "JL-1002", team: "Paralegal", today: "ON_BREAK" },
  { first: "Sneha", last: "Iyer", code: "JL-1003", team: "Paralegal", today: "DAY_ENDED" },
  { first: "Arjun", last: "Nair", code: "JL-1004", team: "Paralegal", today: "NOT_STARTED" },
  { first: "Kavya", last: "Reddy", code: "JL-1005", team: "Paralegal", today: "WORKING" },
  { first: "Vikram", last: "Singh", code: "JL-1101", team: "Sales", today: "WORKING" },
  { first: "Meera", last: "Joshi", code: "JL-1102", team: "Sales", today: "DAY_ENDED" },
  { first: "Rohan", last: "Gupta", code: "JL-1103", team: "Sales", today: "STALE" },
  { first: "Ananya", last: "Das", code: "JL-1104", team: "Sales", today: "ON_BREAK" },
  { first: "Karan", last: "Mehta", code: "JL-1201", team: "Technology", today: "WORKING" },
  { first: "Isha", last: "Kapoor", code: "JL-1202", team: "Technology", today: "NOT_STARTED" },
  { first: "Aditya", last: "Rao", code: "JL-1203", team: "Technology", today: "DAY_ENDED" },
];

const MANAGERS = [
  { first: "Anjali", last: "Menon", email: `manager.paralegal@${DOMAIN}`, code: "JL-9001", team: "Paralegal" },
  { first: "Suresh", last: "Pillai", email: `manager.sales@${DOMAIN}`, code: "JL-9002", team: "Sales" },
  { first: "Deepa", last: "Krishnan", email: `manager.tech@${DOMAIN}`, code: "JL-9003", team: "Technology" },
] as const;

const TEAMS = [
  { name: "Paralegal", description: "Legal research, document review and case support." },
  { name: "Sales", description: "Client acquisition and account management." },
  { name: "Technology", description: "Platform engineering and internal tools." },
] as const;

interface Interval {
  kind: "WORK" | "BREAK";
  start: Date;
  end: Date | null;
}

/** A typical completed workday: work blocks separated by 1–3 breaks. */
function typicalDay(dateKey: string): Interval[] {
  const start = zonedTimeToUtc(dateKey, TZ, 8, 45 + between(0, 50));
  const breaks = between(1, 3);
  const intervals: Interval[] = [];
  let t = start.getTime();
  for (let i = 0; i < breaks; i++) {
    const work = between(95, 165) * MIN;
    intervals.push({ kind: "WORK", start: new Date(t), end: new Date(t + work) });
    t += work;
    const brk = (i === 1 ? between(30, 45) : between(10, 20)) * MIN;
    intervals.push({ kind: "BREAK", start: new Date(t), end: new Date(t + brk) });
    t += brk;
  }
  const lastWork = between(110, 180) * MIN;
  intervals.push({ kind: "WORK", start: new Date(t), end: new Date(t + lastWork) });
  return intervals;
}

/** Today's state, anchored to the current time so nothing is in the future. */
function todayScenario(scenario: Scenario, now: number): Interval[] {
  const at = (minsAgo: number) => new Date(now - minsAgo * MIN);
  switch (scenario) {
    case "WORKING": {
      const s = between(150, 300);
      return [
        { kind: "WORK", start: at(s), end: at(s - 100) },
        { kind: "BREAK", start: at(s - 100), end: at(s - 115) },
        { kind: "WORK", start: at(s - 115), end: null },
      ];
    }
    case "ON_BREAK": {
      const s = between(180, 260);
      const onBreakFor = between(8, 25);
      return [
        { kind: "WORK", start: at(s), end: at(s - 120) },
        { kind: "BREAK", start: at(s - 120), end: at(s - 135) },
        { kind: "WORK", start: at(s - 135), end: at(onBreakFor) },
        { kind: "BREAK", start: at(onBreakFor), end: null },
      ];
    }
    case "DAY_ENDED": {
      const s = between(560, 600);
      return [
        { kind: "WORK", start: at(s), end: at(s - 150) },
        { kind: "BREAK", start: at(s - 150), end: at(s - 165) },
        { kind: "WORK", start: at(s - 165), end: at(s - 300) },
        { kind: "BREAK", start: at(s - 300), end: at(s - 345) },
        { kind: "WORK", start: at(s - 345), end: at(between(10, 40)) },
      ];
    }
    case "STALE": {
      // Started ~26h ago and never ended — shows the "not ended" review flow.
      return [
        { kind: "WORK", start: at(26 * 60), end: at(26 * 60 - 140) },
        { kind: "BREAK", start: at(26 * 60 - 140), end: at(26 * 60 - 160) },
        { kind: "WORK", start: at(26 * 60 - 160), end: null },
      ];
    }
    default:
      return [];
  }
}

async function createWorkday(employeeId: string, teamId: string, intervals: Interval[]) {
  const first = intervals[0]!;
  const last = intervals[intervals.length - 1]!;
  const open = last.end === null;
  const endedAt = open ? null : last.end;
  const work = intervals.filter((i) => i.kind === "WORK").map((i) => ({ startedAt: i.start, endedAt: i.end }));
  const brk = intervals.filter((i) => i.kind === "BREAK").map((i) => ({ startedAt: i.start, endedAt: i.end }));
  const totals = endedAt ? computeTotals({ startedAt: first.start, endedAt }, work, brk, endedAt) : null;

  const wd = await db.workday.create({
    data: {
      employeeId,
      teamId,
      date: dateKeyToDb(toDateKey(first.start, TZ)),
      timezone: TZ,
      startedAt: first.start,
      endedAt,
      status: open ? (last.kind === "BREAK" ? "ON_BREAK" : "WORKING") : "COMPLETED",
      activeEmployeeId: open ? employeeId : null,
      productiveSeconds: totals?.productiveSeconds ?? null,
      breakSeconds: totals?.breakSeconds ?? null,
      breakCount: totals?.breakCount ?? null,
    },
  });
  for (const i of intervals) {
    const data = { employeeId, workdayId: wd.id, startedAt: i.start, endedAt: i.end, openWorkdayId: i.end ? null : wd.id };
    if (i.kind === "WORK") await db.workSession.create({ data });
    else await db.breakSession.create({ data });
  }
  return toDateKey(first.start, TZ);
}

async function main() {
  console.log(`Seeding JurisTrack demo data (timezone ${TZ})…`);

  // Wipe in dependency order.
  await db.auditLog.deleteMany();
  await db.workSession.deleteMany();
  await db.breakSession.deleteMany();
  await db.workday.deleteMany();
  await db.teamMembership.deleteMany();
  await db.session.deleteMany();
  await db.team.deleteMany();
  await db.user.deleteMany();

  await db.organizationSetting.upsert({
    where: { id: 1 },
    update: { organizationName: "Juris LPO", timezone: TZ, staleWorkdayHours: 14 },
    create: { id: 1, organizationName: "Juris LPO", timezone: TZ, staleWorkdayHours: 14 },
  });

  const passwordHash = await hashPassword(PASSWORD);
  const now = Date.now();
  const today = toDateKey(new Date(now), TZ);

  const admin = await db.user.create({
    data: { firstName: "Demo", lastName: "Administrator", email: `admin@${DOMAIN}`, employeeCode: "JL-0001", role: "ADMIN", passwordHash, isDemo: true },
  });

  const teamIds: Record<string, string> = {};
  for (const [i, t] of TEAMS.entries()) {
    const mgr = MANAGERS[i]!;
    const manager = await db.user.create({
      data: { firstName: mgr.first, lastName: mgr.last, email: mgr.email, employeeCode: mgr.code, role: "MANAGER", passwordHash, isDemo: true },
    });
    const team = await db.team.create({
      data: { name: t.name, nameKey: t.name.toLowerCase(), description: t.description, managerId: manager.id },
    });
    teamIds[t.name] = team.id;
    await db.auditLog.create({
      data: { actorId: admin.id, action: "TEAM_CREATED", entityType: "Team", entityId: team.id, after: { name: t.name, managerId: manager.id }, reason: "Demo seed" },
    });
  }

  const historyStart = new Date(now - HISTORY_DAYS * 86_400_000);
  for (const p of PEOPLE) {
    const email = `${p.first}.${p.last}@${DOMAIN}`.toLowerCase();
    const user = await db.user.create({
      data: { firstName: p.first, lastName: p.last, email, employeeCode: p.code, role: "EMPLOYEE", passwordHash, isDemo: true, lastLoginAt: new Date(now - between(1, 20) * 3_600_000) },
    });
    const teamId = teamIds[p.team]!;
    await db.teamMembership.create({
      data: { teamId, employeeId: user.id, activeEmployeeId: user.id, startedAt: historyStart, assignedById: admin.id },
    });

    const used = new Set<string>();
    const scenario = todayScenario(p.today, now);
    if (scenario.length) used.add(await createWorkday(user.id, teamId, scenario));

    // Completed history on weekdays before today, skipping dates already used.
    for (let d = HISTORY_DAYS; d >= 1; d--) {
      const key = addDays(today, -d);
      const dow = new Date(`${key}T00:00:00Z`).getUTCDay();
      if (dow === 0 || dow === 6 || used.has(key)) continue;
      if (rand() < 0.06) continue; // occasional leave day
      used.add(await createWorkday(user.id, teamId, typicalDay(key)));
    }
  }

  const counts = await Promise.all([db.user.count(), db.team.count(), db.workday.count(), db.workSession.count(), db.breakSession.count()]);
  console.log(`Created ${counts[0]} users, ${counts[1]} teams, ${counts[2]} workdays, ${counts[3]} work sessions, ${counts[4]} break sessions.`);
  console.log("\nDemo accounts (development only) — password:", PASSWORD);
  console.log(`  Admin     admin@${DOMAIN}`);
  for (const m of MANAGERS) console.log(`  Manager   ${m.email}  (${m.team})`);
  for (const p of PEOPLE) console.log(`  Employee  ${`${p.first}.${p.last}@${DOMAIN}`.toLowerCase()}  (${p.team}, today: ${p.today})`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => db.$disconnect());
