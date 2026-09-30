import { addDays, isDateKey, startOfMonth, startOfWeek, type DateKey } from "./tz";

export const RANGE_PRESETS = ["today", "yesterday", "week", "month", "custom"] as const;
export type RangePreset = (typeof RANGE_PRESETS)[number];

export const RANGE_LABELS: Record<RangePreset, string> = {
  today: "Today",
  yesterday: "Yesterday",
  week: "This week",
  month: "This month",
  custom: "Custom range",
};

export interface DateRange {
  preset: RangePreset;
  from: DateKey;
  to: DateKey;
}

/** Maximum span of a single query, to keep reports bounded. */
export const MAX_RANGE_DAYS = 366;

type Param = string | string[] | undefined;
const one = (v: Param) => (Array.isArray(v) ? v[0] : v);

/**
 * Resolves a date range from URL search params relative to "today" in the
 * organization timezone. Invalid input falls back to the default preset.
 */
export function resolveRange(
  params: { range?: Param; from?: Param; to?: Param },
  today: DateKey,
  fallback: RangePreset = "today",
): DateRange {
  const raw = one(params.range);
  const preset: RangePreset = (RANGE_PRESETS as readonly string[]).includes(raw ?? "") ? (raw as RangePreset) : fallback;

  switch (preset) {
    case "today":
      return { preset, from: today, to: today };
    case "yesterday": {
      const y = addDays(today, -1);
      return { preset, from: y, to: y };
    }
    case "week":
      return { preset, from: startOfWeek(today), to: today };
    case "month":
      return { preset, from: startOfMonth(today), to: today };
    case "custom": {
      let from = one(params.from);
      let to = one(params.to);
      if (!isDateKey(from)) from = startOfWeek(today);
      if (!isDateKey(to)) to = today;
      if (from > to) [from, to] = [to, from];
      const minFrom = addDays(to, -(MAX_RANGE_DAYS - 1));
      if (from < minFrom) from = minFrom;
      return { preset, from, to };
    }
  }
}
