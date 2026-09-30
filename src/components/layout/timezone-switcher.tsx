"use client";

import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { setDisplayTimezoneAction } from "@/app/actions/preferences";
import { useServerNow } from "@/components/time/use-server-now";
import { useToast } from "@/components/ui/toast";
import { cn } from "@/lib/cn";
import { formatTime } from "@/lib/time/format";
import { zoneAbbr, zoneLongName } from "@/lib/time/zones";

/**
 * Lets the viewer choose which timezone times are displayed in. Each option
 * shows that zone's current time so the difference is obvious at a glance.
 */
export function TimezoneSwitcher({ zones, current, serverNow }: { zones: string[]; current: string; serverNow: string }) {
  const nowMs = useServerNow(serverNow, 15_000);
  const [pending, start] = useTransition();
  const router = useRouter();
  const toast = useToast();
  const now = new Date(nowMs);

  if (zones.length < 2) return null;

  const choose = (tz: string) =>
    start(async () => {
      const res = await setDisplayTimezoneAction(tz);
      if (res.ok) router.refresh();
      else toast.error(res.error);
    });

  return (
    <div className="px-3">
      <p id="tz-label" className="mb-2 text-[11px] font-semibold tracking-wider text-white/40 uppercase">
        Show times in
      </p>
      <div role="radiogroup" aria-labelledby="tz-label" className={cn("space-y-1", pending && "opacity-60")}>
        {zones.map((tz) => {
          const active = tz === current;
          return (
            <button
              key={tz}
              type="button"
              role="radio"
              aria-checked={active}
              disabled={pending}
              onClick={() => !active && choose(tz)}
              title={zoneLongName(tz, now)}
              className={cn(
                "flex w-full items-center justify-between gap-2 rounded-lg px-3 py-1.5 text-left text-sm transition-colors",
                active ? "bg-white/12 text-white" : "text-white/65 hover:bg-white/6 hover:text-white",
              )}
            >
              <span className="flex min-w-0 items-center gap-2">
                <span className={cn("size-1.5 shrink-0 rounded-full", active ? "bg-[#c9a060]" : "bg-white/25")} aria-hidden="true" />
                <span className="truncate">{zoneLongName(tz, now).replace(/ (Standard|Daylight) Time$/, "")}</span>
              </span>
              <span className="tabular shrink-0 text-xs text-white/60" suppressHydrationWarning>
                {formatTime(now, tz)} {zoneAbbr(tz, now)}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
