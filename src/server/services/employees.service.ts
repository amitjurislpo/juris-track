import type { Prisma, Role } from "@/generated/prisma/client";
import { db, type Tx } from "@/lib/db";
import { AppError } from "@/lib/errors";
import type { SessionUser } from "@/lib/auth/session";
import { generateTemporaryPassword, hashPassword, verifyPassword } from "@/lib/auth/password";
import type { EmployeeCreateInput, EmployeeUpdateInput } from "@/lib/validation/schemas";
import { recordAudit } from "./audit.service";
import { employeeScopeWhere } from "./scope.service";
import { closeOpenWorkday } from "./time-corrections.service";

/** Fields safe to return to the UI — never the password hash. */
export const publicUserSelect = {
  id: true,
  firstName: true,
  lastName: true,
  email: true,
  employeeCode: true,
  role: true,
  isActive: true,
  isDemo: true,
  lastLoginAt: true,
  createdAt: true,
} satisfies Prisma.UserSelect;

function requireAdmin(actor: SessionUser) {
  if (actor.role !== "ADMIN") throw new AppError("FORBIDDEN", "Only administrators can manage employees.");
}

async function assertUnique(tx: Tx, input: { email: string; employeeCode?: string }, exceptId?: string) {
  const fieldErrors: Record<string, string[]> = {};
  const byEmail = await tx.user.findUnique({ where: { email: input.email }, select: { id: true } });
  if (byEmail && byEmail.id !== exceptId) fieldErrors.email = ["An account with this email already exists"];
  if (input.employeeCode) {
    const byCode = await tx.user.findUnique({ where: { employeeCode: input.employeeCode }, select: { id: true } });
    if (byCode && byCode.id !== exceptId) fieldErrors.employeeCode = ["This employee ID is already in use"];
  }
  if (Object.keys(fieldErrors).length) throw new AppError("CONFLICT", "Some details are already in use.", fieldErrors);
}

// ─── Queries ─────────────────────────────────────────────────────────────────

export interface EmployeeListQuery {
  q?: string;
  teamId?: string;
  role?: Role;
  status?: "active" | "inactive" | "all";
  page?: number;
  pageSize?: number;
}

export async function listEmployees(actor: SessionUser, query: EmployeeListQuery = {}) {
  const pageSize = Math.min(Math.max(query.pageSize ?? 25, 5), 100);
  const page = Math.max(query.page ?? 1, 1);
  const q = query.q?.trim();
  const status = query.status ?? "active";
  const where: Prisma.UserWhereInput = {
    AND: [
      employeeScopeWhere(actor),
      status === "all" ? {} : { isActive: status === "active" },
      query.role ? { role: query.role } : {},
      query.teamId === "none"
        ? { memberships: { none: { endedAt: null } } }
        : query.teamId
          ? { memberships: { some: { endedAt: null, teamId: query.teamId } } }
          : {},
      q
        ? {
            OR: [
              { firstName: { contains: q, mode: "insensitive" } },
              { lastName: { contains: q, mode: "insensitive" } },
              { email: { contains: q, mode: "insensitive" } },
              { employeeCode: { contains: q, mode: "insensitive" } },
            ],
          }
        : {},
    ],
  };
  const [total, rows] = await Promise.all([
    db.user.count({ where }),
    db.user.findMany({
      where,
      orderBy: [{ isActive: "desc" }, { firstName: "asc" }, { lastName: "asc" }],
      skip: (page - 1) * pageSize,
      take: pageSize,
      select: {
        ...publicUserSelect,
        memberships: { where: { endedAt: null }, select: { team: { select: { id: true, name: true } } }, take: 1 },
      },
    }),
  ]);
  return {
    total,
    page,
    pageSize,
    rows: rows.map(({ memberships, ...u }) => ({ ...u, team: memberships[0]?.team ?? null })),
  };
}

export async function getEmployee(actor: SessionUser, id: string) {
  const user = await db.user.findFirst({
    where: { AND: [{ id }, employeeScopeWhere(actor)] },
    select: {
      ...publicUserSelect,
      mustChangePassword: true,
      memberships: {
        orderBy: { startedAt: "desc" },
        take: 10,
        select: { id: true, startedAt: true, endedAt: true, team: { select: { id: true, name: true, manager: { select: { id: true, firstName: true, lastName: true } } } } },
      },
      managedTeams: { select: { id: true, name: true, isActive: true } },
    },
  });
  if (!user) throw new AppError("NOT_FOUND", "Employee not found.");
  const current = user.memberships.find((m) => !m.endedAt) ?? null;
  return { ...user, team: current?.team ?? null };
}

/** Options for pickers: active users by role. */
export async function listUserOptions(roles: Role[]) {
  return db.user.findMany({
    where: { isActive: true, role: { in: roles } },
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
}

/** Filter options: employees within the actor's scope. */
export async function scopedEmployeeOptions(actor: SessionUser, opts: { includeInactive?: boolean } = {}) {
  const rows = await db.user.findMany({
    where: { AND: [employeeScopeWhere(actor), { role: { in: ["EMPLOYEE", "MANAGER"] } }, opts.includeInactive ? {} : { isActive: true }] },
    orderBy: [{ firstName: "asc" }, { lastName: "asc" }],
    select: { id: true, firstName: true, lastName: true },
  });
  return rows.map((r) => ({ value: r.id, label: `${r.firstName} ${r.lastName}` }));
}

// ─── Mutations ───────────────────────────────────────────────────────────────

/** Moves an employee to a team (or unassigns), preserving membership history. */
export async function assignTeamTx(tx: Tx, actor: SessionUser, employeeId: string, teamId: string | null) {
  const current = await tx.teamMembership.findUnique({
    where: { activeEmployeeId: employeeId },
    include: { team: { select: { id: true, name: true } } },
  });
  if ((current?.teamId ?? null) === teamId) return false;

  let target: { id: string; name: string } | null = null;
  if (teamId) {
    target = await tx.team.findFirst({ where: { id: teamId, isActive: true }, select: { id: true, name: true } });
    if (!target) throw new AppError("VALIDATION", "Select an active team.", { teamId: ["Select an active team"] });
  }

  const now = new Date();
  if (current) {
    await tx.teamMembership.update({ where: { id: current.id }, data: { endedAt: now, activeEmployeeId: null } });
  }
  if (target) {
    await tx.teamMembership.create({
      data: { teamId: target.id, employeeId, activeEmployeeId: employeeId, startedAt: now, assignedById: actor.id },
    });
  }
  await recordAudit(tx, {
    actorId: actor.id,
    subjectId: employeeId,
    action: "EMPLOYEE_MOVED",
    entityType: "User",
    entityId: employeeId,
    before: { team: current?.team ?? null },
    after: { team: target },
  });
  return true;
}

export async function createEmployee(actor: SessionUser, input: EmployeeCreateInput) {
  requireAdmin(actor);
  const temporaryPassword = input.password ? null : generateTemporaryPassword();
  const passwordHash = await hashPassword(input.password ?? temporaryPassword!);

  const user = await db.$transaction(async (tx) => {
    await assertUnique(tx, input);
    const created = await tx.user.create({
      data: {
        firstName: input.firstName,
        lastName: input.lastName,
        email: input.email,
        employeeCode: input.employeeCode ?? null,
        role: input.role,
        isActive: input.isActive,
        passwordHash,
        mustChangePassword: true,
      },
      select: publicUserSelect,
    });
    await recordAudit(tx, {
      actorId: actor.id,
      subjectId: created.id,
      action: "EMPLOYEE_CREATED",
      entityType: "User",
      entityId: created.id,
      after: { name: `${created.firstName} ${created.lastName}`, email: created.email, role: created.role, isActive: created.isActive },
    });
    if (input.teamId) await assignTeamTx(tx, actor, created.id, input.teamId);
    return created;
  });
  return { user, temporaryPassword };
}

export async function updateEmployee(actor: SessionUser, input: EmployeeUpdateInput) {
  requireAdmin(actor);
  const roleChanged = await db.$transaction(async (tx) => {
    const before = await tx.user.findUnique({ where: { id: input.id }, select: publicUserSelect });
    if (!before) throw new AppError("NOT_FOUND", "Employee not found.");
    await assertUnique(tx, input, input.id);

    if (before.role !== input.role) {
      if (before.id === actor.id) throw new AppError("VALIDATION", "You cannot change your own role.", { role: ["You cannot change your own role"] });
      if (before.role === "ADMIN") await assertAnotherActiveAdmin(tx, before.id);
      if (before.role === "MANAGER") {
        // A demoted manager no longer owns teams; clear ownership explicitly.
        const teams = await tx.team.findMany({ where: { managerId: before.id }, select: { id: true, name: true } });
        for (const t of teams) {
          await tx.team.update({ where: { id: t.id }, data: { managerId: null } });
          await recordAudit(tx, {
            actorId: actor.id,
            subjectId: before.id,
            action: "TEAM_MANAGER_CHANGED",
            entityType: "Team",
            entityId: t.id,
            before: { managerId: before.id },
            after: { managerId: null },
            reason: "Manager role removed",
          });
        }
      }
    }

    const after = await tx.user.update({
      where: { id: input.id },
      data: {
        firstName: input.firstName,
        lastName: input.lastName,
        email: input.email,
        employeeCode: input.employeeCode ?? null,
        role: input.role,
      },
      select: publicUserSelect,
    });

    const pick = (u: typeof before) => ({ firstName: u.firstName, lastName: u.lastName, email: u.email, employeeCode: u.employeeCode, role: u.role });
    await recordAudit(tx, {
      actorId: actor.id,
      subjectId: after.id,
      action: before.role !== after.role ? "EMPLOYEE_ROLE_CHANGED" : "EMPLOYEE_UPDATED",
      entityType: "User",
      entityId: after.id,
      before: pick(before),
      after: pick(after),
    });
    return before.role !== after.role;
  });
  // A role change alters permissions; force re-authentication.
  if (roleChanged) await db.session.deleteMany({ where: { userId: input.id } });
}

async function assertAnotherActiveAdmin(tx: Tx, exceptId: string) {
  const others = await tx.user.count({ where: { role: "ADMIN", isActive: true, id: { not: exceptId } } });
  if (others === 0) throw new AppError("INVALID_STATE", "The organization must keep at least one active administrator.");
}

export async function setEmployeeActive(actor: SessionUser, id: string, active: boolean, reason?: string) {
  requireAdmin(actor);
  if (id === actor.id && !active) throw new AppError("INVALID_STATE", "You cannot deactivate your own account.");

  const user = await db.user.findUnique({ where: { id }, select: { id: true, isActive: true, role: true } });
  if (!user) throw new AppError("NOT_FOUND", "Employee not found.");
  if (user.isActive === active) return;

  if (!active) {
    // End any open workday first so no record is left running for a
    // deactivated account.
    const open = await db.workday.findUnique({ where: { activeEmployeeId: id }, select: { id: true } });
    if (open) await closeOpenWorkday(actor, open.id, null, reason ?? "Employee deactivated");
  }

  await db.$transaction(async (tx) => {
    if (!active && user.role === "ADMIN") await assertAnotherActiveAdmin(tx, id);
    await tx.user.update({ where: { id }, data: { isActive: active } });
    if (!active) await tx.session.deleteMany({ where: { userId: id } });
    await recordAudit(tx, {
      actorId: actor.id,
      subjectId: id,
      action: active ? "EMPLOYEE_REACTIVATED" : "EMPLOYEE_DEACTIVATED",
      entityType: "User",
      entityId: id,
      before: { isActive: !active },
      after: { isActive: active },
      reason: reason ?? null,
    });
  });
}

export async function assignTeam(actor: SessionUser, employeeId: string, teamId: string | null) {
  requireAdmin(actor);
  const exists = await db.user.count({ where: { id: employeeId } });
  if (!exists) throw new AppError("NOT_FOUND", "Employee not found.");
  return db.$transaction((tx) => assignTeamTx(tx, actor, employeeId, teamId));
}

export async function resetPassword(actor: SessionUser, id: string): Promise<string> {
  requireAdmin(actor);
  const temporaryPassword = generateTemporaryPassword();
  const passwordHash = await hashPassword(temporaryPassword);
  await db.$transaction(async (tx) => {
    const user = await tx.user.findUnique({ where: { id }, select: { id: true } });
    if (!user) throw new AppError("NOT_FOUND", "Employee not found.");
    await tx.user.update({ where: { id }, data: { passwordHash, mustChangePassword: true } });
    await tx.session.deleteMany({ where: { userId: id } });
    await recordAudit(tx, { actorId: actor.id, subjectId: id, action: "PASSWORD_RESET", entityType: "User", entityId: id });
  });
  return temporaryPassword;
}

export async function changeOwnPassword(userId: string, currentPassword: string, newPassword: string) {
  const user = await db.user.findUnique({ where: { id: userId }, select: { passwordHash: true } });
  if (!user || !(await verifyPassword(currentPassword, user.passwordHash))) {
    throw new AppError("VALIDATION", "Current password is incorrect.", { currentPassword: ["Current password is incorrect"] });
  }
  await db.user.update({
    where: { id: userId },
    data: { passwordHash: await hashPassword(newPassword), mustChangePassword: false },
  });
}
