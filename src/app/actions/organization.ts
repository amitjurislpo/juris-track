"use server";

import { redirect } from "next/navigation";
import { runAction } from "@/lib/action";
import { authorize } from "@/lib/auth/guards";
import { db } from "@/lib/db";
import { AppError } from "@/lib/errors";
import { SettingsSchema, formToObject } from "@/lib/validation/schemas";
import { recordAudit } from "@/server/services/audit.service";

/** First-run: the administrator creates the organization (once). */
export async function createOrganizationAction(form: FormData) {
  const result = await runAction(async () => {
    const actor = await authorize("ADMIN");
    const input = SettingsSchema.parse({ ...formToObject(form), displayTimezones: form.getAll("displayTimezones") });
    await db.$transaction(async (tx) => {
      if (await tx.organizationSetting.findUnique({ where: { id: 1 }, select: { id: true } })) {
        throw new AppError("INVALID_STATE", "The organization has already been created. Edit it in Settings.");
      }
      const org = await tx.organizationSetting.create({ data: { id: 1, ...input } });
      await recordAudit(tx, {
        actorId: actor.id,
        action: "ORGANIZATION_CREATED",
        entityType: "OrganizationSetting",
        entityId: "1",
        after: {
          organizationName: org.organizationName,
          timezone: org.timezone,
          staleWorkdayHours: org.staleWorkdayHours,
          displayTimezones: org.displayTimezones.join(", "),
        },
      });
    });
    return null;
  }, "Organization created.");
  if (result.ok) redirect("/admin");
  return result;
}
