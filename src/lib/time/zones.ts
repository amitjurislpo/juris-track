/**
 * Display-timezone helpers (client-safe). Labels are derived from Intl so any
 * IANA zone works; "America/New_York" shows EDT or EST depending on the date.
 */

const initialsOf = (name: string) =>
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
