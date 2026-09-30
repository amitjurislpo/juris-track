"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { timeCommandAction } from "@/app/actions/time";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/badge";
import { Card, CardHeader, CardBody } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Icon } from "@/components/ui/icons";
import { Callout } from "@/components/ui/misc";
import { useToast } from "@/components/ui/toast";
import { cn } from "@/lib/cn";
import { formatClock, formatDateKey, formatDuration, formatTime } from "@/lib/time/format";
import { liveTotals } from "@/lib/time/live";
import { zoneAbbr } from "@/lib/time/zones";
import { ACTIVITY_LABELS, type EmployeeTimeState, type TimeCommand } from "@/lib/time/types";
import { Timeline } from "./timeline";
import { useServerNow } from "./use-server-now";

const POLL_MS = 30_000;

/**
 * The employee's primary control surface. Displayed durations are derived
 * from the server snapshot's timestamps; the snapshot is refreshed after
 * every command, every 30 s, when the tab becomes visible again, and when
 * the network reconnects — so another tab or device stays in sync.
 */
export function TimerPanel({ initialState, displayTimezone }: { initialState: EmployeeTimeState; displayTimezone: string }) {
  const [state, setState] = useState(initialState);
  const [pending, setPending] = useState<TimeCommand | null>(null);
  const [confirmEnd, setConfirmEnd] = useState(false);
  const [offline, setOffline] = useState(false);
  const inFlight = useRef(false);
  const toast = useToast();
  const router = useRouter();
  const nowMs = useServerNow(state.serverNow);

  const refresh = useCallback(async () => {
    try {
      const res = await fetch("/api/me/status", { cache: "no-store" });
      if (res.status === 401) {
        router.replace("/login");
        return;
      }
      const body = (await res.json()) as { ok: boolean; data?: EmployeeTimeState };
      if (body.ok && body.data && !inFlight.current) setState(body.data);
      setOffline(false);
    } catch {
      setOffline(true);
    }
  }, [router]);

  useEffect(() => {
    const timer = setInterval(refresh, POLL_MS);
    const onVisible = () => {
      if (document.visibilityState === "visible") void refresh();
    };
    const onOnline = () => void refresh();
    const onOffline = () => setOffline(true);
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
    };
  }, [refresh]);

  async function run(command: TimeCommand) {
    // Guard against double clicks before React re-renders the disabled state.
    if (inFlight.current) return;
    inFlight.current = true;
    setPending(command);
    try {
      const res = await timeCommandAction(command);
      if (res.ok) {
        setState(res.data.state);
        if (res.data.alreadyApplied) toast.info("Already recorded — your timer is up to date.");
        else if (res.message) toast.success(res.message);
        setOffline(false);
        router.refresh();
      } else {
        toast.error(res.error);
        if (res.code === "UNAUTHENTICATED") router.replace("/login");
      }
    } catch {
      setOffline(true);
      toast.error("Could not reach the server. Your time is safe — please try again.");
    } finally {
      inFlight.current = false;
      setPending(null);
      setConfirmEnd(false);
      void refresh();
    }
  }

  const wd = state.workday;
  const live = wd ? liveTotals(wd, nowMs) : null;
  const status = state.status;
  // Display only; workday dates and stored data use the organization timezone.
  const tz = displayTimezone;

  const hero =
    status === "ON_BREAK"
      ? { label: "Current break", seconds: live!.currentBreakSeconds }
      : status === "DAY_ENDED"
        ? { label: "Today's productive time", seconds: live!.productiveSeconds }
        : { label: "Productive time today", seconds: live?.productiveSeconds ?? 0 };

  return (
    <div className="space-y-6">
      {wd?.isStale && (
        <Callout tone="warning" title={`Your workday has been open for ${formatDuration(live?.spanSeconds ?? wd.spanSeconds)}`}>
          It started {formatDateKey(wd.date)} at {formatTime(wd.startedAt, tz)}. If you have finished, end it now; if the recorded
          time is wrong, ask an administrator to correct it.
        </Callout>
      )}
      {offline && (
        <Callout tone="info" title="Connection lost — reconnecting">
          Your time is recorded on the server. The timer keeps counting from your last known state.
        </Callout>
      )}

      <Card className="overflow-hidden">
        <div
          className={cn(
            "h-1",
            status === "WORKING" && "bg-st-working",
            status === "ON_BREAK" && "bg-st-break",
            status === "DAY_ENDED" && "bg-st-ended",
            status === "NOT_STARTED" && "bg-border",
          )}
        />
        <div className="grid gap-8 p-6 md:p-8 lg:grid-cols-[1fr_auto] lg:items-center">
          <div>
            <div className="flex flex-wrap items-center gap-3">
              <StatusBadge status={status} size="lg" />
              {wd && (
                <span className="text-sm text-ink-3">
                  Started {formatTime(wd.startedAt, tz)}
                  {wd.endedAt && <> · Ended {formatTime(wd.endedAt, tz)}</>} {zoneAbbr(tz, new Date(wd.startedAt))}
                </span>
              )}
            </div>
            <p className="mt-6 text-sm font-medium text-ink-3">{hero.label}</p>
            <p
              className={cn(
                "tabular font-mono text-6xl leading-none font-semibold tracking-tight md:text-7xl",
                status === "NOT_STARTED" ? "text-ink-3" : "text-ink",
              )}
              aria-live="off"
              suppressHydrationWarning
            >
              {formatClock(hero.seconds)}
            </p>
            {status === "ON_BREAK" && live && (
              <p className="tabular mt-3 text-sm text-ink-2">
                Productive time is paused at <strong className="font-semibold text-ink">{formatClock(live.productiveSeconds)}</strong>
              </p>
            )}
            {status === "NOT_STARTED" && <p className="mt-3 text-sm text-ink-2">Press Start Work when you begin your day.</p>}
          </div>

          <div className="flex flex-col items-stretch gap-3 lg:w-64">
            {status === "NOT_STARTED" && (
              <Button variant="success" size="xl" onClick={() => run("start")} loading={pending === "start"} disabled={!!pending}>
                <Icon name="play" className="size-5" /> Start Work
              </Button>
            )}
            {status === "WORKING" && (
              <>
                <Button variant="warning" size="xl" onClick={() => run("break")} loading={pending === "break"} disabled={!!pending}>
                  <Icon name="coffee" className="size-5" /> Take Break
                </Button>
                <Button variant="secondary" size="lg" onClick={() => setConfirmEnd(true)} disabled={!!pending}>
                  <Icon name="stop" className="size-4" /> End Workday
                </Button>
              </>
            )}
            {status === "ON_BREAK" && (
              <>
                <Button variant="success" size="xl" onClick={() => run("resume")} loading={pending === "resume"} disabled={!!pending}>
                  <Icon name="play" className="size-5" /> Back to Work
                </Button>
                <Button variant="secondary" size="lg" disabled title="Return from your break before ending your workday">
                  <Icon name="stop" className="size-4" /> End Workday
                </Button>
                <p className="text-center text-xs text-ink-3">Return from your break before ending your day.</p>
              </>
            )}
            {status === "DAY_ENDED" && (
              <div className="rounded-xl border border-border bg-surface-2 px-4 py-3 text-center text-sm text-ink-2">
                <Icon name="check" className="mx-auto mb-1 size-5 text-st-ended" />
                Your workday is complete. See you tomorrow.
              </div>
            )}
          </div>
        </div>

        {wd && live && (
          <dl className="grid grid-cols-2 gap-px border-t border-border bg-border sm:grid-cols-3 lg:grid-cols-5">
            {[
              { label: "Productive", value: formatDuration(live.productiveSeconds) },
              { label: "Break", value: formatDuration(live.breakSeconds) },
              { label: "Breaks", value: String(live.breakCount) },
              {
                label: status === "ON_BREAK" ? "Current break" : status === "WORKING" ? "Current session" : "Worked span",
                value:
                  status === "ON_BREAK"
                    ? formatDuration(live.currentBreakSeconds)
                    : status === "WORKING"
                      ? formatDuration(live.currentWorkSeconds)
                      : formatDuration(live.spanSeconds),
              },
              { label: "Last activity", value: `${ACTIVITY_LABELS[wd.lastActivity.kind]} · ${formatTime(wd.lastActivity.at, tz)}` },
            ].map((s) => (
              <div key={s.label} className="bg-surface px-6 py-4 last:max-sm:col-span-2 last:sm:max-lg:col-span-2">
                <dt className="text-xs font-medium tracking-wide text-ink-3 uppercase">{s.label}</dt>
                <dd className="tabular mt-1 text-base font-semibold text-ink" suppressHydrationWarning>
                  {s.value}
                </dd>
              </div>
            ))}
          </dl>
        )}
      </Card>

      {wd && live && status === "DAY_ENDED" && (
        <Card>
          <CardHeader title="Today's Summary" description={formatDateKey(wd.date)} />
          <CardBody>
            <dl className="grid grid-cols-2 gap-6 md:grid-cols-3 lg:grid-cols-6">
              {[
                { label: "Workday start", value: formatTime(wd.startedAt, tz) },
                { label: "Workday end", value: formatTime(wd.endedAt, tz) },
                { label: "Total worked span", value: formatDuration(wd.spanSeconds) },
                { label: "Productive hours", value: formatDuration(wd.productiveSeconds) },
                { label: "Break hours", value: formatDuration(wd.breakSeconds) },
                { label: "Number of breaks", value: String(wd.breakCount) },
              ].map((s) => (
                <div key={s.label}>
                  <dt className="text-xs font-medium tracking-wide text-ink-3 uppercase">{s.label}</dt>
                  <dd className="tabular mt-1 text-xl font-semibold text-ink">{s.value}</dd>
                </div>
              ))}
            </dl>
          </CardBody>
        </Card>
      )}

      {wd && (
        <Card>
          <CardHeader title="Today's timeline" description="Every work and break interval, as recorded." />
          <CardBody>
            <Timeline segments={wd.segments} nowMs={nowMs} timezone={tz} />
          </CardBody>
        </Card>
      )}

      <ConfirmDialog
        open={confirmEnd}
        onClose={() => setConfirmEnd(false)}
        onConfirm={() => run("end")}
        loading={pending === "end"}
        tone="primary"
        title="End your workday?"
        description="Your final productive and break hours will be recorded. You can't start another workday today."
        confirmLabel="End Workday"
      >
        {live && (
          <p className="tabular rounded-lg bg-surface-2 px-3 py-2 text-sm text-ink-2">
            Productive so far: <strong className="text-ink">{formatDuration(live.productiveSeconds)}</strong> · Breaks:{" "}
            <strong className="text-ink">{formatDuration(live.breakSeconds)}</strong>
          </p>
        )}
      </ConfirmDialog>
    </div>
  );
}
