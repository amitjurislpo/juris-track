import Link from "next/link";
import type { HTMLAttributes, TdHTMLAttributes, ThHTMLAttributes } from "react";
import { cn } from "@/lib/cn";
import { Icon } from "./icons";

export function Table({ className, ...rest }: HTMLAttributes<HTMLTableElement>) {
  return (
    <div className="overflow-x-auto">
      <table className={cn("w-full border-collapse text-sm", className)} {...rest} />
    </div>
  );
}

export function THead(props: HTMLAttributes<HTMLTableSectionElement>) {
  return <thead className="bg-surface-2" {...props} />;
}

export function Th({ className, align, ...rest }: ThHTMLAttributes<HTMLTableCellElement> & { align?: "left" | "right" | "center" }) {
  return (
    <th
      scope="col"
      className={cn(
        "border-b border-border px-4 py-2.5 text-xs font-medium tracking-wide whitespace-nowrap text-ink-3 uppercase",
        align === "right" ? "text-right" : align === "center" ? "text-center" : "text-left",
        className,
      )}
      {...rest}
    />
  );
}

export function Tr({ className, ...rest }: HTMLAttributes<HTMLTableRowElement>) {
  return <tr className={cn("border-b border-border last:border-b-0 hover:bg-surface-2/60", className)} {...rest} />;
}

export function Td({ className, align, ...rest }: TdHTMLAttributes<HTMLTableCellElement> & { align?: "left" | "right" | "center" }) {
  return (
    <td
      className={cn(
        "px-4 py-3 align-middle text-ink",
        align === "right" ? "tabular text-right" : align === "center" ? "text-center" : "text-left",
        className,
      )}
      {...rest}
    />
  );
}

export function Pagination({
  page,
  pageSize,
  total,
  hrefFor,
}: {
  page: number;
  pageSize: number;
  total: number;
  hrefFor: (page: number) => string;
}) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  if (pages <= 1) return null;
  const from = (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);
  const link = "inline-flex h-8 items-center gap-1 rounded-md border border-border-strong px-3 text-sm";
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between border-t border-border px-4 py-3 text-sm text-ink-3">
      <span className="tabular">
        {from}–{to} of {total}
      </span>
      <div className="flex gap-2">
        {page > 1 ? (
          <Link href={hrefFor(page - 1)} className={cn(link, "hover:bg-surface-2")}>
            <Icon name="chevronLeft" className="size-4" /> Previous
          </Link>
        ) : (
          <span className={cn(link, "opacity-40")}>
            <Icon name="chevronLeft" className="size-4" /> Previous
          </span>
        )}
        {page < pages ? (
          <Link href={hrefFor(page + 1)} className={cn(link, "hover:bg-surface-2")}>
            Next <Icon name="chevronRight" className="size-4" />
          </Link>
        ) : (
          <span className={cn(link, "opacity-40")}>
            Next <Icon name="chevronRight" className="size-4" />
          </span>
        )}
      </div>
    </nav>
  );
}
