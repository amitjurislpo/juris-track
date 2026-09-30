import type { Prisma } from "@/generated/prisma/client";
import { db, type Tx } from "@/lib/db";

export const AUDIT_ACTIONS = {
  EMPLOYEE_CREATED: "Employee created",
  EMPLOYEE_UPDATED: "Employee updated",
  EMPLOYEE_DEACTIVATED: "Employee deactivated",
  EMPLOYEE_REACTIVATED: "Employee reactivated",
  EMPLOYEE_MOVED: "Employee moved between teams",
  EMPLOYEE_ROLE_CHANGED: "Employee role changed",
  PASSWORD_RESET: "Password reset by administrator",
  TEAM_CREATED: "Team created",
  TEAM_UPDATED: "Team changed",
  TEAM_MANAGER_CHANGED: "Team manager reassigned",
  TEAM_DEACTIVATED: "Team deactivated",
  TEAM_REACTIVATED: "Team reactivated",
  TEAM_DELETED: "Team deleted",
  TIME_ADJUSTED: "Time record adjusted",
  WORKDAY_CLOSED_BY_ADMIN: "Workday closed by administrator",
  ORGANIZATION_CREATED: "Organization created",
  SETTINGS_UPDATED: "Organization settings changed",
} as const;

export type AuditActionKey = keyof typeof AUDIT_ACTIONS;

export interface AuditEntry {
  actorId: string | null;
  subjectId?: string | null;
  action: AuditActionKey;
  entityType: "User" | "Team" | "Workday" | "WorkSession" | "BreakSession" | "OrganizationSetting";
  entityId?: string | null;
  before?: Prisma.InputJsonValue;
  after?: Prisma.InputJsonValue;
  reason?: string | null;
}

/** Appends an audit record. Pass the transaction so it commits atomically with the change. */
export async function recordAudit(tx: Tx, entry: AuditEntry): Promise<void> {
  await tx.auditLog.create({
    data: {
      actorId: entry.actorId,
      subjectId: entry.subjectId ?? null,
      action: entry.action,
      entityType: entry.entityType,
      entityId: entry.entityId ?? null,
      before: entry.before,
      after: entry.after,
      reason: entry.reason ?? null,
    },
  });
}

export interface AuditQuery {
  action?: string;
  subjectId?: string;
  page?: number;
  pageSize?: number;
}

export async function listAuditLogs(q: AuditQuery) {
  const pageSize = Math.min(Math.max(q.pageSize ?? 50, 10), 200);
  const page = Math.max(q.page ?? 1, 1);
  const where: Prisma.AuditLogWhereInput = {
    ...(q.action && q.action in AUDIT_ACTIONS ? { action: q.action } : {}),
    ...(q.subjectId ? { subjectId: q.subjectId } : {}),
  };
  const [total, rows] = await Promise.all([
    db.auditLog.count({ where }),
    db.auditLog.findMany({
      where,
      orderBy: { createdAt: "desc" },
      skip: (page - 1) * pageSize,
      take: pageSize,
      include: {
        actor: { select: { id: true, firstName: true, lastName: true } },
        subject: { select: { id: true, firstName: true, lastName: true } },
      },
    }),
  ]);
  return { total, page, pageSize, rows };
}
