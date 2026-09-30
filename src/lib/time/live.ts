import type { WorkdaySnapshot } from "./types";

/**
 * Advances a server snapshot to `nowMs` using only its timestamps. This is
 * what the live timers display; the database remains the source of truth.
 */
export function liveTotals(
  wd: Pick<
    WorkdaySnapshot,
    "closedProductiveSeconds" | "closedBreakSeconds" | "activeWorkStartedAt" | "activeBreakStartedAt" | "startedAt" | "endedAt" | "breakCount"
  >,
  nowMs: number,
) {
  const since = (iso: string | null) => (iso ? Math.max(0, Math.floor((nowMs - Date.parse(iso)) / 1000)) : 0);
  const currentWork = since(wd.activeWorkStartedAt);
  const currentBreak = since(wd.activeBreakStartedAt);
  const end = wd.endedAt ? Date.parse(wd.endedAt) : nowMs;
  return {
    productiveSeconds: wd.closedProductiveSeconds + currentWork,
    breakSeconds: wd.closedBreakSeconds + currentBreak,
    currentWorkSeconds: currentWork,
    currentBreakSeconds: currentBreak,
    spanSeconds: Math.max(0, Math.floor((end - Date.parse(wd.startedAt)) / 1000)),
    breakCount: wd.breakCount,
  };
}
