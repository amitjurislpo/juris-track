import type { ReactNode } from "react";
import { cn } from "@/lib/cn";
import type { LiveStatus } from "@/lib/time/types";
import { LIVE_STATUS_LABELS } from "@/lib/time/types";

type Tone = "neutral" | "brand" | "success" | "warning" | "danger" | "accent";

const tones: Record<Tone, string> = {
  neutral: "bg-surface-3 text-ink-2",
  brand: "bg-st-ended-bg text-st-ended",
  success: "bg-success-bg text-success",
  warning: "bg-warning-bg text-warning",
  danger: "bg-danger-bg text-danger",
  accent: "bg-accent-soft text-accent",
};

export function Badge({ tone = "neutral", children, className }: { tone?: Tone; children: ReactNode; className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap", tones[tone], className)}>
      {children}
    </span>
  );
}

const statusStyles: Record<LiveStatus, { chip: string; dot: string }> = {
  WORKING: { chip: "bg-st-working-bg text-st-working", dot: "bg-st-working" },
  ON_BREAK: { chip: "bg-st-break-bg text-st-break", dot: "bg-st-break" },
  NOT_STARTED: { chip: "bg-st-idle-bg text-st-idle", dot: "bg-st-idle/60" },
  DAY_ENDED: { chip: "bg-st-ended-bg text-st-ended", dot: "bg-st-ended" },
};

/** Status is always conveyed by label + shape, never color alone. */
export function StatusBadge({ status, size = "md", className }: { status: LiveStatus; size?: "md" | "lg"; className?: string }) {
  const s = statusStyles[status];
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full font-semibold whitespace-nowrap uppercase tracking-wide",
        size === "lg" ? "px-3 py-1 text-xs" : "px-2 py-0.5 text-[11px]",
        s.chip,
        className,
      )}
    >
      <span className={cn("relative inline-flex size-2 rounded-full", s.dot)}>
        {status === "WORKING" && <span className="absolute inset-0 animate-ping rounded-full bg-st-working opacity-40" />}
        {status === "ON_BREAK" && <span className="absolute inset-[3px] rounded-full bg-st-break-bg" />}
      </span>
      {LIVE_STATUS_LABELS[status]}
    </span>
  );
}
