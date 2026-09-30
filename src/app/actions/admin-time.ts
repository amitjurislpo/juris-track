"use server";

import { revalidatePath } from "next/cache";
import { runAction } from "@/lib/action";
import { authorize } from "@/lib/auth/guards";
import { AppError } from "@/lib/errors";
import { parseZonedDateTime } from "@/lib/time/tz";
import { AdjustSessionSchema, CloseWorkdaySchema, SettingsSchema, formToObject } from "@/lib/validation/schemas";
import { getSettings } from "@/server/services/settings.service";
import { adjustSession, closeOpenWorkday } from "@/server/services/time-corrections.service";
import { db } from "@/lib/db";
import { recordAudit } from "@/server/services/audit.service";

/** The zone the admin entered times in: must be one the organization allows. */
async function inputTimezone(form: FormData): Promise<string> {
  const { timezone, displayTimezones } = await getSettings();
  const submitted = form.get("timezone");
  return typeof submitted === "string" && displayTimezones.includes(submitted) ? submitted : timezone;
}

function parseTime(value: string, tz: string, field: string): Date {
  const d = parseZonedDateTime(value, tz);
  if (!d) throw new AppError("VALIDATION", "Enter a valid date and time.", { [field]: ["Enter a valid date and time"] });
  return d;
}

export async function adjustSessionAction(form: FormData) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    const input = AdjustSessionSchema.parse(formToObject(form));
    const timezone = await inputTimezone(form);
    await adjustSession(actor, {
      kind: input.kind,
      sessionId: input.sessionId,
      startedAt: parseTime(input.startedAt, timezone, "startedAt"),
      endedAt: input.endedAt ? parseTime(input.endedAt, timezone, "endedAt") : null,
      reason: input.reason,
    });
    revalidatePath("/", "layout");
    return null;
  }, "Time record adjusted and logged.");
}

export async function closeWorkdayAction(form: FormData) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    const input = CloseWorkdaySchema.parse(formToObject(form));
    const timezone = await inputTimezone(form);
    const endAt = input.endAt ? parseTime(input.endAt, timezone, "endAt") : null;
    await closeOpenWorkday(actor, input.workdayId, endAt, input.reason);
    revalidatePath("/", "layout");
    return null;
  }, "Workday closed and logged.");
}

export async function updateSettingsAction(form: FormData) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    const input = SettingsSchema.parse({ ...formToObject(form), displayTimezones: form.getAll("displayTimezones") });
    await db.$transaction(async (tx) => {
      const before = await tx.organizationSetting.findUnique({ where: { id: 1 } });
      const after = await tx.organizationSetting.upsert({ where: { id: 1 }, update: input, create: { id: 1, ...input } });
      await recordAudit(tx, {
        actorId: actor.id,
        action: "SETTINGS_UPDATED",
        entityType: "OrganizationSetting",
        entityId: "1",
        before: before
          ? {
              organizationName: before.organizationName,
              timezone: before.timezone,
              staleWorkdayHours: before.staleWorkdayHours,
              displayTimezones: before.displayTimezones.join(", "),
            }
          : undefined,
        after: {
          organizationName: after.organizationName,
          timezone: after.timezone,
          staleWorkdayHours: after.staleWorkdayHours,
          displayTimezones: after.displayTimezones.join(", "),
        },
      });
    });
    revalidatePath("/", "layout");
    return null;
  }, "Settings saved.");
}
