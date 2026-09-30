/**
 * Pure time calculations shared by server and client.
 *
 * Durations are always derived from interval timestamps:
 *   closed interval  -> endedAt - startedAt
 *   open interval    -> now - startedAt
 * and then summed. Nothing here increments a counter.
 */

export interface IntervalLike {
  startedAt: Date | string;
  endedAt: Date | string | null;
}

const toMs = (v: Date | string) => (typeof v === "string" ? Date.parse(v) : v.getTime());

export function intervalSeconds(interval: IntervalLike, now: Date | number): number {
  const start = toMs(interval.startedAt);
  const end = interval.endedAt ? toMs(interval.endedAt) : typeof now === "number" ? now : now.getTime();
  return Math.max(0, Math.floor((end - start) / 1000));
}

export function sumSeconds(intervals: readonly IntervalLike[], now: Date | number): number {
  return intervals.reduce((acc, i) => acc + intervalSeconds(i, now), 0);
}

/** Sum of closed intervals only (open intervals contribute 0). */
export function closedSeconds(intervals: readonly IntervalLike[]): number {
  return intervals.reduce((acc, i) => (i.endedAt ? acc + intervalSeconds(i, 0) : acc), 0);
}

export function openInterval<T extends IntervalLike>(intervals: readonly T[]): T | undefined {
  return intervals.find((i) => !i.endedAt);
}

export interface WorkdayTotals {
  productiveSeconds: number;
  breakSeconds: number;
  spanSeconds: number;
  breakCount: number;
}

export function computeTotals(
  workday: IntervalLike,
  workSessions: readonly IntervalLike[],
  breakSessions: readonly IntervalLike[],
  now: Date | number,
): WorkdayTotals {
  return {
    productiveSeconds: sumSeconds(workSessions, now),
    breakSeconds: sumSeconds(breakSessions, now),
    spanSeconds: intervalSeconds(workday, now),
    breakCount: breakSessions.length,
  };
}
