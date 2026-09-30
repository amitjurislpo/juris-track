"use client";

import { useEffect, useRef, useState } from "react";

/**
 * A ticking "now" aligned to the server clock.
 *
 * Each time a fresh `serverNow` arrives, the offset between server and
 * client clocks is re-measured, so a wrong or drifting client clock never
 * affects the displayed durations. The value is recomputed from the clock on
 * every tick (not incremented), so sleeping/backgrounded tabs catch up
 * instantly when they wake.
 */
export function useServerNow(serverNow: string, intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.parse(serverNow));
  const offset = useRef(0);

  useEffect(() => {
    offset.current = Date.parse(serverNow) - Date.now();
    const tick = () => setNow(Date.now() + offset.current);
    const first = setTimeout(tick, 0);
    const timer = setInterval(tick, intervalMs);
    const onVisible = () => {
      if (document.visibilityState === "visible") tick();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearTimeout(first);
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [serverNow, intervalMs]);

  return now;
}
