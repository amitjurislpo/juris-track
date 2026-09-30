import { cache } from "react";
import { db } from "@/lib/db";
import { isValidTimeZone, toDateKey } from "@/lib/time/tz";

export interface OrgSettings {
  organizationName: string;
  timezone: string;
  staleWorkdayHours: number;
  /** Zones viewers may display times in; always includes the org timezone first. */
  displayTimezones: string[];
}

function defaultTimezone() {
  const tz = process.env.ORG_TIMEZONE ?? "Asia/Kolkata";
  return isValidTimeZone(tz) ? tz : "UTC";
}

async function loadSettings(): Promise<OrgSettings> {
  const row =
    (await db.organizationSetting.findUnique({ where: { id: 1 } })) ??
    (await db.organizationSetting.upsert({
      where: { id: 1 },
      update: {},
      create: { id: 1, timezone: defaultTimezone() },
    }));
  const displayTimezones = [...new Set([row.timezone, ...row.displayTimezones])].filter(isValidTimeZone);
  return {
    organizationName: row.organizationName,
    timezone: row.timezone,
    staleWorkdayHours: row.staleWorkdayHours,
    displayTimezones,
  };
}

/** Organization settings, memoized per request. */
export const getSettings = cache(loadSettings);

/** Today's date key in the organization timezone. */
export async function orgToday(now = new Date()): Promise<{ today: string; timezone: string; settings: OrgSettings }> {
  const settings = await getSettings();
  return { today: toDateKey(now, settings.timezone), timezone: settings.timezone, settings };
}
