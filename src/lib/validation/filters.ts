import { isDateKey } from "@/lib/time/tz";
import { LIVE_STATUSES, type LiveStatus } from "@/lib/time/types";

type Param = string | string[] | null | undefined;

const first = (v: Param) => (Array.isArray(v) ? v[0] : (v ?? undefined));

/** Opaque id from a query string: bounded length and a safe charset. */
export function idParam(v: Param): string | undefined {
  const s = first(v);
  return s && /^[A-Za-z0-9_-]{1,64}$/.test(s) ? s : undefined;
}

export function dateParam(v: Param): string | undefined {
  const s = first(v);
  return isDateKey(s) ? s : undefined;
}

export function statusParam(v: Param): LiveStatus | undefined {
  const s = first(v);
  return (LIVE_STATUSES as readonly string[]).includes(s ?? "") ? (s as LiveStatus) : undefined;
}

export function stringParam(v: Param, max = 100): string | undefined {
  const s = first(v)?.trim();
  return s ? s.slice(0, max) : undefined;
}

export function pageParam(v: Param): number {
  const n = Number(first(v));
  return Number.isInteger(n) && n > 0 && n < 10_000 ? n : 1;
}

export function oneOf<T extends string>(v: Param, options: readonly T[], fallback: T): T {
  const s = first(v);
  return (options as readonly string[]).includes(s ?? "") ? (s as T) : fallback;
}
