import Link from "next/link";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";
import { Icon, type IconName } from "./icons";

export function PageHeader({
  title,
  description,
  actions,
  back,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  back?: { href: string; label: string };
}) {
  return (
    <div className="mb-6">
      {back && (
        <Link href={back.href} className="mb-2 inline-flex items-center gap-1 text-sm text-ink-3 hover:text-ink">
          <Icon name="chevronLeft" className="size-4" />
          {back.label}
        </Link>
      )}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight text-ink">{title}</h1>
          {description && <p className="mt-1 text-sm text-ink-3">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  );
}

export function EmptyState({
  icon = "info",
  title,
  description,
  action,
  className,
}: {
  icon?: IconName;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col items-center justify-center px-6 py-12 text-center", className)}>
      <div className="mb-3 rounded-full bg-surface-3 p-3 text-ink-3">
        <Icon name={icon} className="size-6" />
      </div>
      <p className="text-sm font-semibold text-ink">{title}</p>
      {description && <p className="mt-1 max-w-sm text-sm text-ink-3">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function StatCard({
  label,
  value,
  hint,
  tone,
  className,
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  tone?: "working" | "break" | "idle" | "ended";
  className?: string;
}) {
  const dot = tone
    ? { working: "bg-st-working", break: "bg-st-break", idle: "bg-st-idle/60", ended: "bg-st-ended" }[tone]
    : null;
  return (
    <div className={cn("rounded-xl border border-border bg-surface px-4 py-4", className)}>
      <p className="flex items-center gap-2 text-xs font-medium tracking-wide text-ink-3 uppercase">
        {dot && <span className={cn("size-2 rounded-full", dot)} aria-hidden="true" />}
        {label}
      </p>
      <p className="tabular mt-2 text-2xl font-semibold tracking-tight text-ink">{value}</p>
      {hint && <p className="mt-1 text-xs text-ink-3">{hint}</p>}
    </div>
  );
}

export function Callout({
  tone = "info",
  title,
  children,
  action,
}: {
  tone?: "info" | "warning" | "danger";
  title: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
}) {
  const styles = {
    info: "border-st-ended/25 bg-st-ended-bg text-st-ended",
    warning: "border-warning/30 bg-warning-bg text-warning",
    danger: "border-danger/30 bg-danger-bg text-danger",
  }[tone];
  return (
    <div role={tone === "info" ? "status" : "alert"} className={cn("flex flex-wrap items-start gap-3 rounded-xl border px-4 py-3", styles)}>
      <Icon name={tone === "info" ? "info" : "alert"} className="mt-0.5 size-5 shrink-0" />
      <div className="min-w-0 flex-1 text-sm">
        <p className="font-semibold">{title}</p>
        {children && <div className="mt-0.5 text-ink-2">{children}</div>}
      </div>
      {action}
    </div>
  );
}

export function DescriptionList({ items }: { items: Array<{ label: string; value: ReactNode }> }) {
  return (
    <dl className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2">
      {items.map((i) => (
        <div key={i.label}>
          <dt className="text-xs font-medium tracking-wide text-ink-3 uppercase">{i.label}</dt>
          <dd className="mt-1 text-sm text-ink">{i.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Avatar({ name, className }: { name: { firstName: string; lastName: string }; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cn("inline-flex size-8 shrink-0 items-center justify-center rounded-full bg-surface-3 text-xs font-semibold text-ink-2", className)}
    >
      {`${name.firstName[0] ?? ""}${name.lastName[0] ?? ""}`.toUpperCase()}
    </span>
  );
}
