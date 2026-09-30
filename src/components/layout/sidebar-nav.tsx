"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";
import { Icon } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import type { NavItem } from "@/lib/auth/roles";

function isActive(pathname: string, href: string, all: NavItem[]) {
  if (pathname === href) return true;
  if (!pathname.startsWith(`${href}/`)) return false;
  // Prefer the most specific matching item (e.g. /admin/teams over /admin).
  return !all.some((i) => i.href !== href && i.href.startsWith(href) && (pathname === i.href || pathname.startsWith(`${i.href}/`)));
}

export function SidebarNav({ items }: { items: NavItem[] }) {
  const pathname = usePathname();
  return (
    <nav aria-label="Main" className="space-y-0.5">
      {items.map((item) => {
        const active = isActive(pathname, item.href, items);
        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={active ? "page" : undefined}
            className={cn(
              "flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors",
              active ? "bg-white/12 text-white" : "text-white/70 hover:bg-white/6 hover:text-white",
            )}
          >
            <Icon name={item.icon} className="size-[18px]" />
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}

/** Off-canvas wrapper so the sidebar collapses on narrow screens. */
export function MobileSidebar({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const pathname = usePathname();
  const [lastPath, setLastPath] = useState(pathname);
  if (pathname !== lastPath) {
    setLastPath(pathname);
    setOpen(false);
  }
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="rounded-md p-2 text-ink-2 hover:bg-surface-3 lg:hidden"
        aria-label="Open navigation"
      >
        <Icon name="menu" />
      </button>
      {open && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true">
          <div className="absolute inset-0 bg-black/40" onClick={() => setOpen(false)} />
          <div className="absolute inset-y-0 left-0 w-72">{children}</div>
        </div>
      )}
    </>
  );
}
