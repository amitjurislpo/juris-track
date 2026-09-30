import type { DateKey } from "./tz";

/** 02:37:14 — used for live timers. Hours may exceed 24. */
export function formatClock(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
}

/** 7h 48m — used for totals in tables and cards. */
export function formatDuration(totalSeconds: number | null | undefined): string {
  if (totalSeconds == null) return "—";
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h === 0 && m === 0) return s > 0 ? "<1m" : "0m";
  if (h === 0) return `${m}m`;
  return `${h}h ${String(m).padStart(2, "0")}m`;
}

/** Decimal hours (e.g. 8.50) for reports and exports. */
export function toDecimalHours(totalSeconds: number): string {
  return (totalSeconds / 3600).toFixed(2);
}

const asDate = (v: Date | string) => (typeof v === "string" ? new Date(v) : v);

/** 09:05 in the given timezone. */
export function formatTime(value: Date | string | null | undefined, timeZone: string): string {
  if (!value) return "—";
  return new Intl.DateTimeFormat("en-GB", { timeZone, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(
    asDate(value),
  );
}

/** 1 Oct 2026, 09:05 in the given timezone. */
export function formatDateTime(value: Date | string | null | undefined, timeZone: string): string {
  if (!value) return "—";
  return new Intl.DateTimeFormat("en-GB", {
    timeZone,
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(asDate(value));
}

/** Wed, 1 Oct 2026 — for a calendar date key (timezone-free). */
export function formatDateKey(key: DateKey, opts: { weekday?: boolean; year?: boolean } = {}): string {
  const { weekday = true, year = true } = opts;
  return new Intl.DateTimeFormat("en-GB", {
    timeZone: "UTC",
    weekday: weekday ? "short" : undefined,
    day: "numeric",
    month: "short",
    year: year ? "numeric" : undefined,
  }).format(new Date(`${key}T00:00:00Z`));
}

export function fullName(u: { firstName: string; lastName: string }): string {
  return `${u.firstName} ${u.lastName}`.trim();
}

export function initials(u: { firstName: string; lastName: string }): string {
  return `${u.firstName[0] ?? ""}${u.lastName[0] ?? ""}`.toUpperCase();
}
