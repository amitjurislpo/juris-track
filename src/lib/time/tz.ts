/**
 * Timezone helpers built on the platform Intl API.
 *
 * All calendar logic uses the organization timezone, never the browser's.
 * A "date key" is an ISO calendar date string (YYYY-MM-DD) in that timezone.
 */

export type DateKey = string;

const DATE_KEY_RE = /^\d{4}-\d{2}-\d{2}$/;

const dtfCache = new Map<string, Intl.DateTimeFormat>();

function partsFormatter(timeZone: string) {
  let f = dtfCache.get(timeZone);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    dtfCache.set(timeZone, f);
  }
  return f;
}

export function isValidTimeZone(timeZone: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone });
    return true;
  } catch {
    return false;
  }
}

export function isDateKey(value: unknown): value is DateKey {
  if (typeof value !== "string" || !DATE_KEY_RE.test(value)) return false;
  const d = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === value;
}

function zonedParts(date: Date, timeZone: string) {
  const out: Record<string, number> = {};
  for (const p of partsFormatter(timeZone).formatToParts(date)) {
    if (p.type !== "literal") out[p.type] = Number(p.value);
  }
  return out as { year: number; month: number; day: number; hour: number; minute: number; second: number };
}

/** Offset (ms) of the timezone from UTC at the given instant. */
export function tzOffsetMs(date: Date, timeZone: string): number {
  const p = zonedParts(date, timeZone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return asUtc - Math.floor(date.getTime() / 1000) * 1000;
}

/** Calendar date (YYYY-MM-DD) of an instant in the given timezone. */
export function toDateKey(date: Date, timeZone: string): DateKey {
  const p = zonedParts(date, timeZone);
  return `${p.year}-${String(p.month).padStart(2, "0")}-${String(p.day).padStart(2, "0")}`;
}

/** UTC instant for a wall-clock time on a date in the given timezone. */
export function zonedTimeToUtc(key: DateKey, timeZone: string, hour = 0, minute = 0, second = 0): Date {
  const [y, m, d] = key.split("-").map(Number) as [number, number, number];
  const guess = Date.UTC(y, m - 1, d, hour, minute, second);
  const offset = tzOffsetMs(new Date(guess), timeZone);
  let ts = guess - offset;
  // Re-evaluate once in case the guess straddled a DST transition.
  const offset2 = tzOffsetMs(new Date(ts), timeZone);
  if (offset2 !== offset) ts = guess - offset2;
  return new Date(ts);
}

/** Start-of-day instant for a date key in the given timezone. */
export function startOfDayUtc(key: DateKey, timeZone: string): Date {
  return zonedTimeToUtc(key, timeZone);
}

export function addDays(key: DateKey, days: number): DateKey {
  const d = new Date(`${key}T00:00:00.000Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** Monday-based start of week. */
export function startOfWeek(key: DateKey): DateKey {
  const d = new Date(`${key}T00:00:00.000Z`);
  const dow = (d.getUTCDay() + 6) % 7;
  return addDays(key, -dow);
}

export function startOfMonth(key: DateKey): DateKey {
  return `${key.slice(0, 7)}-01`;
}

export function diffDays(from: DateKey, to: DateKey): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

export function eachDay(from: DateKey, to: DateKey): DateKey[] {
  const out: DateKey[] = [];
  for (let k = from; k <= to; k = addDays(k, 1)) out.push(k);
  return out;
}

const LOCAL_DT_RE = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/;

/**
 * Parses an <input type="datetime-local"> value ("2026-10-01T09:30") as a
 * wall-clock time in the given timezone. Returns null when malformed.
 */
export function parseZonedDateTime(value: string, timeZone: string): Date | null {
  const m = LOCAL_DT_RE.exec(value);
  if (!m || !isDateKey(m[1])) return null;
  const [h, min, s] = [Number(m[2]), Number(m[3]), Number(m[4] ?? 0)];
  if (h > 23 || min > 59 || s > 59) return null;
  return zonedTimeToUtc(m[1]!, timeZone, h, min, s);
}

/** Formats an instant as a datetime-local input value in the given timezone. */
export function toZonedInputValue(date: Date | string, timeZone: string): string {
  const d = typeof date === "string" ? new Date(date) : date;
  const p = zonedParts(d, timeZone);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${p.year}-${pad(p.month)}-${pad(p.day)}T${pad(p.hour)}:${pad(p.minute)}`;
}

/** Converts a date key to the Date value Prisma expects for a @db.Date column. */
export function dateKeyToDb(key: DateKey): Date {
  return new Date(`${key}T00:00:00.000Z`);
}

/** Converts a @db.Date value read from Prisma back into a date key. */
export function dbToDateKey(date: Date): DateKey {
  return date.toISOString().slice(0, 10);
}
