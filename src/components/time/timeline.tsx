import { cn } from "@/lib/cn";
import { formatDuration, formatTime } from "@/lib/time/format";
import type { TimelineSegment } from "@/lib/time/types";

/**
 * Work/break timeline: a proportional bar (with per-segment hover/focus
 * tooltips), a legend, and the same data as an interval list, so the
 * information never depends on color alone.
 */
export function Timeline({
  segments,
  nowMs,
  timezone,
  showList = true,
  compact = false,
}: {
  segments: TimelineSegment[];
  nowMs: number;
  timezone: string;
  showList?: boolean;
  compact?: boolean;
}) {
  if (segments.length === 0) {
    return <p className="text-sm text-ink-3">No activity recorded yet.</p>;
  }

  const startMs = Date.parse(segments[0]!.startedAt);
  const endOf = (s: TimelineSegment) => (s.endedAt ? Date.parse(s.endedAt) : nowMs);
  const endMs = Math.max(startMs + 1000, ...segments.map(endOf));
  const total = endMs - startMs;
  const isOpen = segments.some((s) => !s.endedAt);

  // Hour ticks between start and end (at most ~8 labels).
  const hourMs = 3_600_000;
  const spanHours = total / hourMs;
  const step = spanHours > 10 ? 2 : 1;
  const ticks: number[] = [];
  for (let t = Math.ceil(startMs / hourMs) * hourMs; t < endMs; t += step * hourMs) {
    const pct = ((t - startMs) / total) * 100;
    // Keep clear of the start/end labels so they never collide.
    if (pct > 12 && pct < 88) ticks.push(t);
  }

  return (
    <div className="space-y-4">
      <div>
        <div className={cn("relative w-full overflow-visible rounded-md bg-surface-3", compact ? "h-3" : "h-8")} role="img" aria-label="Work and break timeline">
          {segments.map((s) => {
            const left = ((Date.parse(s.startedAt) - startMs) / total) * 100;
            const width = Math.max(0.4, ((endOf(s) - Date.parse(s.startedAt)) / total) * 100);
            const duration = Math.floor((endOf(s) - Date.parse(s.startedAt)) / 1000);
            const label = `${s.kind === "WORK" ? "Work" : "Break"} ${formatTime(s.startedAt, timezone)} – ${s.endedAt ? formatTime(s.endedAt, timezone) : "now"} (${formatDuration(duration)})`;
            return (
              <div
                key={s.id}
                tabIndex={compact ? undefined : 0}
                aria-label={label}
                className="group absolute inset-y-0 px-px outline-none"
                style={{ left: `${left}%`, width: `${width}%` }}
              >
                <div
                  className={cn(
                    "h-full rounded-[3px]",
                    s.kind === "WORK" ? "bg-mark-work" : "bg-mark-break",
                    !s.endedAt && "animate-pulse [animation-duration:3s]",
                  )}
                />
                {!compact && (
                  <div className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-2 hidden -translate-x-1/2 rounded-lg border border-border bg-surface px-3 py-2 text-xs whitespace-nowrap text-ink shadow-lg group-hover:block group-focus-visible:block">
                    <span className="flex items-center gap-1.5 font-semibold">
                      <span className={cn("size-2 rounded-sm", s.kind === "WORK" ? "bg-mark-work" : "bg-mark-break")} />
                      {s.kind === "WORK" ? "Work" : "Break"}
                    </span>
                    <span className="tabular mt-0.5 block text-ink-2">
                      {formatTime(s.startedAt, timezone)} – {s.endedAt ? formatTime(s.endedAt, timezone) : "now"} · {formatDuration(duration)}
                    </span>
                  </div>
                )}
              </div>
            );
          })}
        </div>
        {!compact && (
          <div className="tabular relative mt-1.5 h-4 text-[11px] text-ink-3">
            <span className="absolute left-0">{formatTime(new Date(startMs), timezone)}</span>
            {ticks.map((t) => (
              <span key={t} className="absolute -translate-x-1/2" style={{ left: `${((t - startMs) / total) * 100}%` }}>
                {formatTime(new Date(t), timezone)}
              </span>
            ))}
            <span className="absolute right-0">{isOpen ? "Now" : formatTime(new Date(endMs), timezone)}</span>
          </div>
        )}
      </div>

      {!compact && (
        <div className="flex items-center gap-4 text-xs text-ink-2">
          <span className="flex items-center gap-1.5">
            <span className="h-2.5 w-4 rounded-sm bg-mark-work" aria-hidden="true" /> Work
          </span>
          <span className="flex items-center gap-1.5">
            <span className="h-2.5 w-4 rounded-sm bg-mark-break" aria-hidden="true" /> Break
          </span>
        </div>
      )}

      {showList && (
        <ol className="divide-y divide-border rounded-lg border border-border">
          {segments.map((s) => {
            const duration = Math.floor((endOf(s) - Date.parse(s.startedAt)) / 1000);
            return (
              <li key={s.id} className="flex items-center gap-3 px-3 py-2 text-sm">
                <span className={cn("w-1 self-stretch rounded-full", s.kind === "WORK" ? "bg-mark-work" : "bg-mark-break")} aria-hidden="true" />
                <span className="tabular w-32 shrink-0 text-ink">
                  {formatTime(s.startedAt, timezone)} → {s.endedAt ? formatTime(s.endedAt, timezone) : <strong className="font-semibold">Now</strong>}
                </span>
                <span className={cn("w-14 text-xs font-semibold tracking-wide uppercase", s.kind === "WORK" ? "text-ink" : "text-ink-2")}>
                  {s.kind === "WORK" ? "Work" : "Break"}
                </span>
                <span className="tabular ml-auto text-ink-2">{formatDuration(duration)}</span>
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}
