import { db, type Tx } from "@/lib/db";
import { AppError } from "@/lib/errors";
import type { SessionUser } from "@/lib/auth/session";
import type { TeamInput } from "@/lib/validation/schemas";
import { recordAudit } from "./audit.service";
import { teamScopeWhere } from "./scope.service";

const nameKeyOf = (name: string) => name.trim().replace(/\s+/g, " ").toLowerCase();

function requireAdmin(actor: SessionUser) {
  if (actor.role !== "ADMIN") throw new AppError("FORBIDDEN", "Only administrators can manage teams.");
}

async function assertManager(tx: Tx, managerId: string | undefined) {
  if (!managerId) return;
  const m = await tx.user.findFirst({ where: { id: managerId, role: "MANAGER", isActive: true }, select: { id: true } });
  if (!m) throw new AppError("VALIDATION", "Select an active manager.", { managerId: ["Select an active manager"] });
}

async function assertNameAvailable(tx: Tx, name: string, exceptId?: string) {
  const existing = await tx.team.findUnique({ where: { nameKey: nameKeyOf(name) }, select: { id: true } });
  if (existing && existing.id !== exceptId) {
    throw new AppError("CONFLICT", "A team with this name already exists.", { name: ["A team with this name already exists"] });
  }
}

export async function listTeams(actor: SessionUser, opts: { includeInactive?: boolean } = {}) {
  const teams = await db.team.findMany({
    where: { AND: [teamScopeWhere(actor), opts.includeInactive ? {} : { isActive: true }] },
    orderBy: [{ isActive: "desc" }, { name: "asc" }],
    include: {
      manager: { select: { id: true, firstName: true, lastName: true } },
      _count: { select: { memberships: { where: { endedAt: null } } } },
    },
  });
  return teams.map(({ _count, ...t }) => ({ ...t, memberCount: _count.memberships }));
}

export async function getTeam(actor: SessionUser, id: string) {
  const team = await db.team.findFirst({
    where: { AND: [{ id }, teamScopeWhere(actor)] },
    include: {
      manager: { select: { id: true, firstName: true, lastName: true, email: true } },
      memberships: {
        where: { endedAt: null },
        orderBy: { employee: { firstName: "asc" } },
        include: {
          employee: { select: { id: true, firstName: true, lastName: true, email: true, employeeCode: true, role: true, isActive: true } },
        },
      },
      _count: { select: { workdays: true } },
    },
  });
  if (!team) throw new AppError("NOT_FOUND", "Team not found.");
  return team;
}

export async function createTeam(actor: SessionUser, input: TeamInput) {
  requireAdmin(actor);
  return db.$transaction(async (tx) => {
    await assertNameAvailable(tx, input.name);
    await assertManager(tx, input.managerId);
    const team = await tx.team.create({
      data: { name: input.name, nameKey: nameKeyOf(input.name), description: input.description ?? null, managerId: input.managerId ?? null },
    });
    await recordAudit(tx, {
      actorId: actor.id,
      action: "TEAM_CREATED",
      entityType: "Team",
      entityId: team.id,
      after: { name: team.name, description: team.description, managerId: team.managerId },
    });
    return team;
  });
}

export async function updateTeam(actor: SessionUser, id: string, input: TeamInput) {
  requireAdmin(actor);
  return db.$transaction(async (tx) => {
    const before = await tx.team.findUnique({ where: { id } });
    if (!before) throw new AppError("NOT_FOUND", "Team not found.");
    await assertNameAvailable(tx, input.name, id);
    await assertManager(tx, input.managerId);

    const after = await tx.team.update({
      where: { id },
      data: { name: input.name, nameKey: nameKeyOf(input.name), description: input.description ?? null, managerId: input.managerId ?? null },
    });
    if (before.name !== after.name || before.description !== after.description) {
      await recordAudit(tx, {
        actorId: actor.id,
        action: "TEAM_UPDATED",
        entityType: "Team",
        entityId: id,
        before: { name: before.name, description: before.description },
        after: { name: after.name, description: after.description },
      });
    }
    if (before.managerId !== after.managerId) {
      await recordAudit(tx, {
        actorId: actor.id,
        subjectId: after.managerId ?? before.managerId,
        action: "TEAM_MANAGER_CHANGED",
        entityType: "Team",
        entityId: id,
        before: { managerId: before.managerId },
        after: { managerId: after.managerId },
      });
    }
    return after;
  });
}

/**
 * Deactivating a team ends its current memberships (members become
 * unassigned; history and historical workday attribution are preserved).
 */
export async function setTeamActive(actor: SessionUser, id: string, active: boolean, reason?: string) {
  requireAdmin(actor);
  await db.$transaction(async (tx) => {
    const team = await tx.team.findUnique({ where: { id } });
    if (!team) throw new AppError("NOT_FOUND", "Team not found.");
    if (team.isActive === active) return;

    let unassigned = 0;
    if (!active) {
      const res = await tx.teamMembership.updateMany({
        where: { teamId: id, endedAt: null },
        data: { endedAt: new Date(), activeEmployeeId: null },
      });
      unassigned = res.count;
    }
    await tx.team.update({ where: { id }, data: { isActive: active } });
    await recordAudit(tx, {
      actorId: actor.id,
      action: active ? "TEAM_REACTIVATED" : "TEAM_DEACTIVATED",
      entityType: "Team",
      entityId: id,
      before: { isActive: team.isActive },
      after: { isActive: active, membersUnassigned: unassigned },
      reason: reason ?? null,
    });
  });
}

/** Permanent deletion is allowed only for teams with no history at all. */
export async function deleteTeam(actor: SessionUser, id: string) {
  requireAdmin(actor);
  await db.$transaction(async (tx) => {
    const team = await tx.team.findUnique({
      where: { id },
      include: { _count: { select: { memberships: true, workdays: true } } },
    });
    if (!team) throw new AppError("NOT_FOUND", "Team not found.");
    if (team._count.memberships > 0 || team._count.workdays > 0) {
      throw new AppError("INVALID_STATE", "This team has membership or time history. Deactivate it instead of deleting.");
    }
    await tx.team.delete({ where: { id } });
    await recordAudit(tx, { actorId: actor.id, action: "TEAM_DELETED", entityType: "Team", entityId: id, before: { name: team.name } });
  });
}
