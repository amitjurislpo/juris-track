import { cookies } from "next/headers";
import { cache } from "react";
import { getSettings } from "@/server/services/settings.service";

/** Cookie holding the viewer's chosen display timezone (a preference, not a secret). */
export const DISPLAY_TZ_COOKIE = "jt_tz";

/**
 * The timezone the current viewer wants times displayed in. Only zones the
 * organization allows are honoured; anything else falls back to the org
 * timezone. Affects display only — workday dates and stored data always use
 * the organization timezone.
 */
export const getViewerTimezone = cache(async (): Promise<string> => {
  const settings = await getSettings();
  const wanted = (await cookies()).get(DISPLAY_TZ_COOKIE)?.value;
  return wanted && settings.displayTimezones.includes(wanted) ? wanted : settings.timezone;
});
