import type { SVGProps } from "react";

/** Minimal inline icon set (stroke icons, 20px grid) — no icon dependency. */
const paths = {
  home: "M3 10.5 10 4l7 6.5V17a1 1 0 0 1-1 1h-3.5v-5h-5v5H4a1 1 0 0 1-1-1z",
  clock: "M10 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm0-12v4l2.5 2.5",
  history: "M3 10a7 7 0 1 0 2.05-4.95M3 3v3.5h3.5M10 6.5V10l2.5 1.5",
  user: "M10 10a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm-6 7.5c0-3 2.7-5 6-5s6 2 6 5",
  users: "M7.5 9.5a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM2 17c0-2.8 2.5-4.5 5.5-4.5S13 14.2 13 17M13 3.8a3 3 0 0 1 0 5.4M15.5 12.8c1.6.6 2.5 2 2.5 4.2",
  team: "M3 5.5A1.5 1.5 0 0 1 4.5 4h11A1.5 1.5 0 0 1 17 5.5v9a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 3 14.5zM3 8.5h14M7.5 4v12",
  chart: "M3 17h14M5.5 14V9M10 14V5M14.5 14v-3",
  settings:
    "M10 12.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm6.2-1.3.9 1.6-1.6 2.7-1.8-.3a6 6 0 0 1-1.4.8L11.6 18H8.4l-.7-2a6 6 0 0 1-1.4-.8l-1.8.3-1.6-2.7.9-1.6a6 6 0 0 1 0-1.6l-.9-1.6 1.6-2.7 1.8.3c.4-.3.9-.6 1.4-.8L8.4 2h3.2l.7 2c.5.2 1 .5 1.4.8l1.8-.3 1.6 2.7-.9 1.6a6 6 0 0 1 0 1.6Z",
  audit: "M6 3h6l4 4v9.5a1.5 1.5 0 0 1-1.5 1.5h-8.5A1.5 1.5 0 0 1 4.5 16.5v-12A1.5 1.5 0 0 1 6 3Zm5.5 0v4.5H16M7.5 11h5M7.5 14h5",
  live: "M10 11.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3ZM6.5 6.5a5 5 0 0 0 0 7M13.5 6.5a5 5 0 0 1 0 7M4 4a8.5 8.5 0 0 0 0 12M16 4a8.5 8.5 0 0 1 0 12",
  play: "M6.5 4.5v11l9-5.5z",
  pause: "M7 4.5v11M13 4.5v11",
  stop: "M5.5 5.5h9v9h-9z",
  coffee: "M4 8h10v4.5a4.5 4.5 0 0 1-4.5 4.5h-1A4.5 4.5 0 0 1 4 12.5zM14 9h1.5a2 2 0 0 1 0 4H14M7 2.5v2.5M10 2.5v2.5",
  plus: "M10 4v12M4 10h12",
  search: "M9 15a6 6 0 1 0 0-12 6 6 0 0 0 0 12Zm8 2-3.8-3.8",
  chevronRight: "m8 5 5 5-5 5",
  chevronLeft: "m12 5-5 5 5 5",
  x: "m5 5 10 10M15 5 5 15",
  check: "m4.5 10.5 3.5 3.5 7.5-8",
  alert: "M10 7v4m0 3h.01M8.6 3.3 2.4 14a1.6 1.6 0 0 0 1.4 2.4h12.4a1.6 1.6 0 0 0 1.4-2.4L11.4 3.3a1.6 1.6 0 0 0-2.8 0Z",
  info: "M10 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm0-9v5m0-8h.01",
  logout: "M8 17H4.5A1.5 1.5 0 0 1 3 15.5v-11A1.5 1.5 0 0 1 4.5 3H8m5 10 4-3-4-3m4 3H8",
  download: "M10 3v10m0 0-4-4m4 4 4-4M4 16h12",
  edit: "M12.5 4.5l3 3L7 16H4v-3zM11 6l3 3",
  menu: "M3 5.5h14M3 10h14M3 14.5h14",
  calendar: "M4.5 5h11A1.5 1.5 0 0 1 17 6.5v9a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 3 15.5v-9A1.5 1.5 0 0 1 4.5 5ZM3 9h14M7 3v4M13 3v4",
  key: "M12.5 11a4 4 0 1 0-3.9-3.1L3 13.5V17h3.5v-2h2v-2h2l.9-.9c.4.1.7.1 1.1.1Zm1-5h.01",
} as const;

export type IconName = keyof typeof paths;

export function Icon({ name, className = "size-5", ...rest }: { name: IconName } & SVGProps<SVGSVGElement>) {
  const filled = name === "play";
  return (
    <svg
      viewBox="0 0 20 20"
      fill={filled ? "currentColor" : "none"}
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className={className}
      {...rest}
    >
      <path d={paths[name]} />
    </svg>
  );
}
