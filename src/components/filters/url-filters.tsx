"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useState, useTransition, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { SelectInput, TextInput } from "@/components/ui/form";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { RANGE_LABELS, RANGE_PRESETS, type DateRange } from "@/lib/time/ranges";

/** Updates URL search params (the source of truth for server-rendered filters). */
function useParamUpdater() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const [pending, start] = useTransition();
  const update = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params.toString());
    for (const [k, v] of Object.entries(changes)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    next.delete("page");
    start(() => router.replace(`${pathname}?${next.toString()}`, { scroll: false }));
  };
  return { update, pending, params };
}

export function FilterBar({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("flex flex-wrap items-end gap-3", className)}>{children}</div>;
}

export function RangeFilter({ range }: { range: DateRange }) {
  const { update, pending } = useParamUpdater();
  const [from, setFrom] = useState(range.from);
  const [to, setTo] = useState(range.to);

  return (
    <div className="flex flex-wrap items-end gap-3">
      <div role="group" aria-label="Date range" className="inline-flex rounded-lg border border-border-strong bg-surface p-0.5 shadow-sm">
        {RANGE_PRESETS.map((p) => (
          <button
            key={p}
            type="button"
            aria-pressed={range.preset === p}
            onClick={() => update(p === "custom" ? { range: p, from, to } : { range: p, from: undefined, to: undefined })}
            className={cn(
              "rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors",
              range.preset === p ? "bg-brand text-brand-ink" : "text-ink-2 hover:bg-surface-3",
            )}
          >
            {RANGE_LABELS[p]}
          </button>
        ))}
      </div>
      {range.preset === "custom" && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            update({ range: "custom", from, to });
          }}
        >
          <TextInput type="date" aria-label="From date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} className="h-9 w-40" />
          <span className="pb-2 text-sm text-ink-3">to</span>
          <TextInput type="date" aria-label="To date" value={to} min={from} onChange={(e) => setTo(e.target.value)} className="h-9 w-40" />
          <Button type="submit" size="sm" variant="secondary" className="h-9">
            Apply
          </Button>
        </form>
      )}
      {pending && <Spinner className="mb-2 size-4 text-ink-3" />}
    </div>
  );
}

export function ParamSelect({
  name,
  label,
  value,
  options,
  allLabel,
  className,
}: {
  name: string;
  label: string;
  value: string | undefined;
  options: Array<{ value: string; label: string }>;
  allLabel?: string;
  className?: string;
}) {
  const { update } = useParamUpdater();
  return (
    <SelectInput
      label={label}
      value={value ?? ""}
      onChange={(e) => update({ [name]: e.target.value || undefined })}
      fieldClassName={cn("min-w-40", className)}
      className="h-9"
    >
      {allLabel !== undefined && <option value="">{allLabel}</option>}
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </SelectInput>
  );
}

export function ParamDate({ name, label, value, max }: { name: string; label: string; value: string; max?: string }) {
  const { update } = useParamUpdater();
  return (
    <TextInput
      type="date"
      label={label}
      value={value}
      max={max}
      onChange={(e) => update({ [name]: e.target.value || undefined })}
      className="h-9 w-44"
    />
  );
}

export function ParamSearch({ name, label, value, placeholder }: { name: string; label: string; value?: string; placeholder?: string }) {
  const { update } = useParamUpdater();
  const [text, setText] = useState(value ?? "");
  return (
    <form
      role="search"
      onSubmit={(e) => {
        e.preventDefault();
        update({ [name]: text.trim() || undefined });
      }}
    >
      <TextInput
        type="search"
        label={label}
        value={text}
        placeholder={placeholder}
        onChange={(e) => setText(e.target.value)}
        onBlur={() => {
          if ((value ?? "") !== text.trim()) update({ [name]: text.trim() || undefined });
        }}
        className="h-9 w-64"
      />
    </form>
  );
}
