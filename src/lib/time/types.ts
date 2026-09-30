/** Client-safe, serializable shapes describing time-tracking state. */

export type LiveStatus = "NOT_STARTED" | "WORKING" | "ON_BREAK" | "DAY_ENDED";

export const LIVE_STATUSES: readonly LiveStatus[] = ["WORKING", "ON_BREAK", "NOT_STARTED", "DAY_ENDED"];

export const LIVE_STATUS_LABELS: Record<LiveStatus, string> = {
  WORKING: "Working",
  ON_BREAK: "On Break",
  NOT_STARTED: "Not Started",
  DAY_ENDED: "Day Ended",
};

export interface TimelineSegment {
  id: string;
  kind: "WORK" | "BREAK";
  startedAt: string;
  endedAt: string | null;
}

export type ActivityKind = "STARTED_WORK" | "STARTED_BREAK" | "RESUMED_WORK" | "ENDED_DAY";

export const ACTIVITY_LABELS: Record<ActivityKind, string> = {
  STARTED_WORK: "Started work",
  STARTED_BREAK: "Started break",
  RESUMED_WORK: "Resumed work",
  ENDED_DAY: "Ended workday",
};

/**
 * Snapshot of a workday as of `asOf` (server time). Live values can be
 * advanced on the client with:
 *   productive = closedProductiveSeconds + (now - activeWorkStartedAt)
 *   break      = closedBreakSeconds      + (now - activeBreakStartedAt)
 */
export interface WorkdaySnapshot {
  id: string;
  date: string;
  status: LiveStatus;
  startedAt: string;
  endedAt: string | null;
  /** Open workday that has run longer than the organization's stale threshold. */
  isStale: boolean;
  closedProductiveSeconds: number;
  closedBreakSeconds: number;
  activeWorkStartedAt: string | null;
  activeBreakStartedAt: string | null;
  productiveSeconds: number;
  breakSeconds: number;
  spanSeconds: number;
  breakCount: number;
  lastActivity: { kind: ActivityKind; at: string };
  segments: TimelineSegment[];
}

export interface EmployeeTimeState {
  employeeId: string;
  serverNow: string;
  today: string;
  timezone: string;
  status: LiveStatus;
  workday: WorkdaySnapshot | null;
}

export type TimeCommand = "start" | "break" | "resume" | "end";

export interface TimeCommandResult {
  state: EmployeeTimeState;
  /** True when the request was a duplicate and no new record was created. */
  alreadyApplied: boolean;
}
