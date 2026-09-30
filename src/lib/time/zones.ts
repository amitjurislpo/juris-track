/**
 * Display-timezone helpers (client-safe). Labels are derived from Intl so any
 * IANA zone works; "America/New_York" shows EDT or EST depending on the date.
 */

/**
 * Intl.supportedValuesOf("timeZone") returns some legacy ICU names (e.g.
 * "Asia/Calcutta"). Offer the modern IANA names instead; both resolve to the
 * same rules.
 */
const MODERN_NAMES: Record<string, string> = {
  "Asia/Calcutta": "Asia/Kolkata",
  "Asia/Saigon": "Asia/Ho_Chi_Minh",
  "Asia/Katmandu": "Asia/Kathmandu",
  "Asia/Rangoon": "Asia/Yangon",
  "Europe/Kiev": "Europe/Kyiv",
  "America/Buenos_Aires": "America/Argentina/Buenos_Aires",
  "America/Godthab": "America/Nuuk",
  "Atlantic/Faeroe": "Atlantic/Faroe",
  "Pacific/Truk": "Pacific/Chuuk",
  "Pacific/Ponape": "Pacific/Pohnpei",
  "Pacific/Enderbury": "Pacific/Kanton",
};

/** Sorted list of selectable IANA zones, always including `include`. */
export function timezoneOptions(include: readonly string[] = []): string[] {
  const listed = Intl.supportedValuesOf("timeZone").map((tz) => MODERN_NAMES[tz] ?? tz);
  return [...new Set([...listed, ...include])].sort((a, b) => a.localeCompare(b));
}

const initialsOf =(name: string) =>
  name
    .split(/\s+/)
    .map((w) => w[0])
    .join("")
    .toUpperCase();

function zoneName(tz: string, style: "short" | "long", at: Date): string {
  const part = new Intl.DateTimeFormat("en-US", { timeZone: tz, timeZoneName: style })
    .formatToParts(at)
    .find((p) => p.type === "timeZoneName");
  return part?.value ?? tz;
}

/** Short abbreviation at an instant: "IST", "EDT", "EST", "GMT", … */
export function zoneAbbr(tz: string, at: Date = new Date()): string {
  const short = zoneName(tz, "short", at);
  // en-US gives "GMT+5:30" for many zones; fall back to initials of the long name.
  if (/^GMT[+-]/.test(short) || /^UTC[+-]/.test(short)) return initialsOf(zoneName(tz, "long", at));
  return short;
}

/** Friendly label: "India Standard Time (IST)". */
export function zoneLabel(tz: string, at: Date = new Date()): string {
  return `${zoneName(tz, "long", at)} (${zoneAbbr(tz, at)})`;
}

/** Long name at an instant: "India Standard Time", "Eastern Daylight Time". */
export function zoneLongName(tz: string, at: Date = new Date()): string {
  return zoneName(tz, "long", at);
}
