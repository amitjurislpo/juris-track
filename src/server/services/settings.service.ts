import { cache } from "react";
import { db } from "@/lib/db";
import { isValidTimeZone, toDateKey } from "@/lib/time/tz";

export interface OrgSettings {
  /** False until an administrator has created the organization. */
  configured: boolean;
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

/** Suggested values for the organization setup form (nothing is written). */
export function setupDefaults(): Omit<OrgSettings, "configured"> {
  const timezone = defaultTimezone();
  return {
    organizationName: "",
    timezone,
    staleWorkdayHours: 14,
    displayTimezones: [...new Set([timezone, "Asia/Kolkata", "America/New_York"])],
  };
}

async function loadSettings(): Promise<OrgSettings> {
  const row = await db.organizationSetting.findUnique({ where: { id: 1 } });
  // Not created yet: the administrator creates the organization on first sign-in.
  if (!row) return { configured: false, ...setupDefaults() };
  const displayTimezones = [...new Set([row.timezone, ...row.displayTimezones])].filter(isValidTimeZone);
  return {
    configured: true,
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
