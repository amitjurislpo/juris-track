import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getSessionUser } from "@/lib/auth/session";
import { homeFor } from "@/lib/auth/roles";
import { LoginForm } from "./login-form";

export const metadata: Metadata = { title: "Sign in" };
export const dynamic = "force-dynamic";

export default async function LoginPage() {
  const user = await getSessionUser();
  if (user) redirect(homeFor(user.role));
  const showDemo = process.env.NODE_ENV !== "production";

  return (
    <div className="grid min-h-screen lg:grid-cols-[1.1fr_1fr]">
      <section className="relative hidden flex-col justify-between overflow-hidden bg-[#0f213d] p-12 text-white lg:flex">
        <div className="flex items-center gap-3">
          <span className="flex size-10 items-center justify-center rounded-lg bg-white/10 ring-1 ring-white/15">
            <svg viewBox="0 0 32 32" className="size-7" aria-hidden="true">
              <path d="M12 8h8v2h-2.5v10.5a4.5 4.5 0 0 1-9 0V19h2.5v1.5a2 2 0 0 0 4 0V10H12z" fill="currentColor" />
              <circle cx="23" cy="22" r="2.5" fill="#c9a060" />
            </svg>
          </span>
          <div>
            <p className="text-lg font-semibold tracking-tight">JurisTrack</p>
            <p className="text-xs tracking-wider text-white/55 uppercase">Juris LPO</p>
          </div>
        </div>
        <div className="max-w-md">
          <h1 className="text-4xl leading-tight font-semibold tracking-tight">Accurate time. Clear visibility. Every workday.</h1>
          <p className="mt-4 text-base leading-relaxed text-white/70">
            Start your day, take breaks, and end your workday with one click. Productive hours are calculated from recorded work
            intervals — never estimated.
          </p>
        </div>
        <p className="text-xs text-white/40">Internal system · Authorized Juris LPO personnel only</p>
        <div className="pointer-events-none absolute -right-24 -bottom-24 size-96 rounded-full border border-white/5" aria-hidden="true" />
        <div className="pointer-events-none absolute -right-8 -bottom-8 size-64 rounded-full border border-white/5" aria-hidden="true" />
      </section>

      <section className="flex items-center justify-center px-4 py-12 sm:px-8">
        <div className="w-full max-w-sm">
          <div className="mb-8 lg:hidden">
            <p className="text-xl font-semibold tracking-tight text-ink">JurisTrack</p>
            <p className="text-xs tracking-wider text-ink-3 uppercase">Juris LPO</p>
          </div>
          <h2 className="text-2xl font-semibold tracking-tight text-ink">Sign in</h2>
          <p className="mt-1 text-sm text-ink-3">Use your Juris LPO work email.</p>
          <div className="mt-8">
            <LoginForm />
          </div>
          {showDemo && (
            <div className="mt-8 rounded-xl border border-dashed border-border-strong bg-surface px-4 py-3 text-xs text-ink-3">
              <p className="font-semibold text-ink-2">Development demo accounts</p>
              <p className="mt-1">
                admin@demo.jurislpo.test · manager.paralegal@demo.jurislpo.test · priya.sharma@demo.jurislpo.test — password from{" "}
                <code className="font-mono">SEED_DEMO_PASSWORD</code> (default <code className="font-mono">Demo@12345</code>).
              </p>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
