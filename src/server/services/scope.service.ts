import type { Prisma } from "@/generated/prisma/client";
import { db } from "@/lib/db";
import { AppError } from "@/lib/errors";
import type { SessionUser } from "@/lib/auth/session";

/**
 * Data-access scope per role, applied inside every query:
 *   ADMIN    → organization-wide
 *   MANAGER  → employees whose *current* team is one the manager owns
 *   EMPLOYEE → only themselves
 */

export function employeeScopeWhere(actor: SessionUser): Prisma.UserWhereInput {
  switch (actor.role) {
    case "ADMIN":
      return {};
    case "MANAGER":
      return { memberships: { some: { endedAt: null, team: { managerId: actor.id } } } };
    default:
      return { id: actor.id };
  }
}

export function teamScopeWhere(actor: SessionUser): Prisma.TeamWhereInput {
  switch (actor.role) {
    case "ADMIN":
      return {};
    case "MANAGER":
      return { managerId: actor.id };
    default:
      return { id: "__none__" };
  }
}

export async function assertCanViewEmployee(actor: SessionUser, employeeId: string): Promise<void> {
  if (actor.role === "ADMIN" || actor.id === employeeId) return;
  const count = await db.user.count({ where: { AND: [{ id: employeeId }, employeeScopeWhere(actor)] } });
  // Respond with NOT_FOUND so out-of-scope ids are indistinguishable from missing ones.
  if (count === 0) throw new AppError("NOT_FOUND", "Employee not found.");
}

export async function assertCanViewTeam(actor: SessionUser, teamId: string): Promise<void> {
  const count = await db.team.count({ where: { AND: [{ id: teamId }, teamScopeWhere(actor)] } });
  if (count === 0) throw new AppError("NOT_FOUND", "Team not found.");
}
