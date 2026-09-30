import Link from "next/link";
import type { ReactNode } from "react";
import { logoutAction } from "@/app/actions/auth";
import { Icon } from "@/components/ui/icons";
import { Avatar } from "@/components/ui/misc";
import type { SessionUser } from "@/lib/auth/session";
import { homeFor, navFor, ROLE_LABELS } from "@/lib/auth/roles";
import { MobileSidebar, SidebarNav } from "./sidebar-nav";
import { TimezoneSwitcher } from "./timezone-switcher";

function Brand({ orgName }: { orgName: string }) {
  return (
    <div className="flex items-center gap-3 px-3">
      <span className="flex size-9 items-center justify-center rounded-lg bg-white/10 text-white ring-1 ring-white/15">
        <svg viewBox="0 0 32 32" className="size-6" aria-hidden="true">
          <path d="M12 8h8v2h-2.5v10.5a4.5 4.5 0 0 1-9 0V19h2.5v1.5a2 2 0 0 0 4 0V10H12z" fill="currentColor" />
          <circle cx="23" cy="22" r="2.5" fill="#c9a060" />
        </svg>
      </span>
      <div className="leading-tight">
        <p className="text-[15px] font-semibold tracking-tight text-white">JurisTrack</p>
        <p className="text-[11px] tracking-wide text-white/55 uppercase">{orgName}</p>
      </div>
    </div>
  );
}

export interface DisplayZones {
  zones: string[];
  current: string;
  serverNow: string;
}

function Sidebar({ user, orgName, tz }: { user: SessionUser; orgName: string; tz: DisplayZones }) {
  return (
    <div className="flex h-full flex-col bg-[#0f213d] px-3 py-5">
      <Link href={homeFor(user.role)} className="mb-8 block">
        <Brand orgName={orgName} />
      </Link>
      <p className="mb-2 px-3 text-[11px] font-semibold tracking-wider text-white/40 uppercase">{ROLE_LABELS[user.role]}</p>
      <SidebarNav items={navFor(user.role)} />
      <div className="mt-auto pt-6">
        <TimezoneSwitcher zones={tz.zones} current={tz.current} serverNow={tz.serverNow} />
      </div>
      <div className="mt-4 border-t border-white/10 pt-4">
        <Link href="/profile" className="flex items-center gap-3 rounded-lg px-3 py-2 hover:bg-white/6">
          <Avatar name={user} className="bg-white/10 text-white" />
          <span className="min-w-0 leading-tight">
            <span className="block truncate text-sm font-medium text-white">
              {user.firstName} {user.lastName}
            </span>
            <span className="block truncate text-xs text-white/55">{user.email}</span>
          </span>
        </Link>
        <form action={logoutAction}>
          <button
            type="submit"
            className="mt-1 flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-white/70 hover:bg-white/6 hover:text-white"
          >
            <Icon name="logout" className="size-[18px]" />
            Sign out
          </button>
        </form>
      </div>
    </div>
  );
}

export function AppShell({ user, orgName, tz, children }: { user: SessionUser; orgName: string; tz: DisplayZones; children: ReactNode }) {
  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[256px_1fr]">
      <aside className="sticky top-0 hidden h-screen lg:block">
        <Sidebar user={user} orgName={orgName} tz={tz} />
      </aside>
      <div className="min-w-0">
        <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-border bg-surface/90 px-4 backdrop-blur lg:hidden">
          <MobileSidebar>
            <Sidebar user={user} orgName={orgName} tz={tz} />
          </MobileSidebar>
          <span className="font-semibold">JurisTrack</span>
        </header>
        <main className="mx-auto w-full max-w-7xl px-4 py-6 sm:px-6 lg:px-10 lg:py-10">{children}</main>
      </div>
    </div>
  );
}
