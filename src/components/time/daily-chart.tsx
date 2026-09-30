import { formatDateKey, formatDuration } from "@/lib/time/format";

export interface DailyChartPoint {
  date: string;
  productiveSeconds: number;
  breakSeconds: number;
}

/**
 * Daily productive hours as columns (single series, so no legend box — the
 * card title names the measure). Each column has a hover/focus tooltip; the
 * accompanying table on the page is the non-visual equivalent.
 */
export function DailyChart({ points, height = 180 }: { points: DailyChartPoint[]; height?: number }) {
  if (points.length === 0) return null;
  const maxHours = Math.max(1, ...points.map((p) => p.productiveSeconds / 3600));
  // "Nice" tick step giving at most ~5 intervals, whatever the scale (1 person or the whole org).
  const tickStep = [1, 2, 4, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000].find((s) => maxHours / s <= 5) ?? Math.ceil(maxHours / 5);
  const top = Math.ceil(maxHours / tickStep) * tickStep;
  const ticks = Array.from({ length: top / tickStep + 1 }, (_, i) => i * tickStep);
  const labelEvery = Math.max(1, Math.ceil(points.length / 7));

  return (
    <div className="flex gap-2">
      <div className="tabular relative w-8 shrink-0 text-right text-[11px] text-ink-3" style={{ height }}>
        {ticks.map((t) => (
          <span key={t} className="absolute right-0" style={{ bottom: `${(t / top) * 100}%`, transform: "translateY(50%)" }}>
            {t}h
          </span>
        ))}
      </div>
      <div className="min-w-0 flex-1">
        <div className="relative" style={{ height }}>
          {ticks.map((t) => (
            <div key={t} className="absolute inset-x-0 border-t border-mark-grid" style={{ bottom: `${(t / top) * 100}%` }} aria-hidden="true" />
          ))}
          <div className="absolute inset-0 flex items-end">
            {points.map((p) => {
              const pct = (p.productiveSeconds / 3600 / top) * 100;
              return (
                <div
                  key={p.date}
                  tabIndex={0}
                  aria-label={`${formatDateKey(p.date)}: ${formatDuration(p.productiveSeconds)} productive, ${formatDuration(p.breakSeconds)} break`}
                  className="group relative flex h-full flex-1 items-end justify-center px-px outline-none"
                >
                  <div className="absolute inset-0 rounded-md group-hover:bg-surface-3/60 group-focus-visible:bg-surface-3/60" aria-hidden="true" />
                  <div
                    className="relative w-full max-w-6 rounded-t-[4px] bg-mark-work"
                    style={{ height: p.productiveSeconds > 0 ? `max(${pct}%, 2px)` : 0 }}
                  />
                  <div className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-1 hidden -translate-x-1/2 rounded-lg border border-border bg-surface px-3 py-2 text-xs whitespace-nowrap shadow-lg group-hover:block group-focus-visible:block">
                    <p className="font-semibold text-ink">{formatDateKey(p.date)}</p>
                    <p className="tabular mt-0.5 text-ink-2">Productive {formatDuration(p.productiveSeconds)}</p>
                    <p className="tabular text-ink-3">Break {formatDuration(p.breakSeconds)}</p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
        {/* Labels are absolutely positioned so they never widen their column. */}
        <div className="mt-1.5 flex h-4">
          {points.map((p, i) => {
            const show = i % labelEvery === 0;
            // On narrow screens only every other label is shown, to avoid collisions.
            const narrowHidden = i % (labelEvery * 2) !== 0 ? "hidden sm:block" : "";
            const edge = i === 0 ? "left-0" : i === points.length - 1 ? "right-0" : "left-1/2 -translate-x-1/2";
            return (
              <span key={p.date} className="relative min-w-0 flex-1">
                {show && (
                  <span className={`tabular absolute top-0 text-[11px] whitespace-nowrap text-ink-3 ${edge} ${narrowHidden}`}>
                    {formatDateKey(p.date, { weekday: points.length <= 7, year: false }).replace(",", "")}
                  </span>
                )}
              </span>
            );
          })}
        </div>
      </div>
    </div>
  );
}
