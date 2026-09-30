import type { Metadata } from "next";
import Link from "next/link";
import { FilterBar, ParamSelect } from "@/components/filters/url-filters";
import { Card } from "@/components/ui/card";
import { EmptyState, PageHeader } from "@/components/ui/misc";
import { Pagination, Table, Td, Th, THead, Tr } from "@/components/ui/table";
import { requireRole } from "@/lib/auth/guards";
import { formatDateTime } from "@/lib/time/format";
import { idParam, pageParam, stringParam } from "@/lib/validation/filters";
import { AUDIT_ACTIONS, listAuditLogs } from "@/server/services/audit.service";
import { getViewerTimezone } from "@/server/viewer-timezone";

export const metadata: Metadata = { title: "Audit Log" };

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

function formatValue(value: unknown, timezone: string): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}T/.test(value)) return formatDateTime(value, timezone);
  if (typeof value === "object") {
    const v = value as Record<string, unknown>;
    if ("name" in v && typeof v.name === "string") return v.name;
    return JSON.stringify(value);
  }
  return String(value);
}

function Changes({ before, after, timezone }: { before: unknown; after: unknown; timezone: string }) {
  const b = (before ?? {}) as Record<string, unknown>;
  const a = (after ?? {}) as Record<string, unknown>;
  const keys = [...new Set([...Object.keys(b), ...Object.keys(a)])].filter((k) => JSON.stringify(b[k]) !== JSON.stringify(a[k]) || !before);
  if (keys.length === 0) return <span className="text-ink-3">—</span>;
  return (
    <ul className="space-y-0.5 text-xs">
      {keys.map((k) => (
        <li key={k}>
          <span className="text-ink-3">{k}: </span>
          {before ? (
            <>
              <span className="text-ink-2 line-through decoration-ink-3/60">{formatValue(b[k], timezone)}</span>
              <span className="text-ink-3"> → </span>
            </>
          ) : null}
          <span className="font-medium text-ink">{formatValue(a[k], timezone)}</span>
        </li>
      ))}
    </ul>
  );
}

export default async function AuditLogPage({ searchParams }: { searchParams: SearchParams }) {
  await requireRole("ADMIN");
  const sp = await searchParams;
  const action = stringParam(sp.action, 64);
  const subjectId = idParam(sp.subjectId);
  const timezone = await getViewerTimezone();
  const log = await listAuditLogs({ action, subjectId, page: pageParam(sp.page) });

  const hrefFor = (p: number) => {
    const qs = new URLSearchParams();
    if (action) qs.set("action", action);
    if (subjectId) qs.set("subjectId", subjectId);
    qs.set("page", String(p));
    return `/admin/audit-log?${qs.toString()}`;
  };

  return (
    <>
      <PageHeader title="Audit Log" description="Every administrative change and time correction, with who made it, when, and why. Entries cannot be edited." />
      <Card>
        <div className="border-b border-border px-5 py-4">
          <FilterBar>
            <ParamSelect name="action" label="Action" value={action} allLabel="All actions" options={Object.entries(AUDIT_ACTIONS).map(([value, label]) => ({ value, label }))} />
            {subjectId && (
              <Link href="/admin/audit-log" className="pb-2 text-sm text-ink-2 underline">
                Clear employee filter
              </Link>
            )}
          </FilterBar>
        </div>
        {log.rows.length === 0 ? (
          <EmptyState icon="audit" title="No audit entries" />
        ) : (
          <>
            <Table>
              <THead>
                <tr>
                  <Th>When</Th>
                  <Th>Action</Th>
                  <Th>By</Th>
                  <Th>Subject</Th>
                  <Th>Changes</Th>
                  <Th>Reason</Th>
                </tr>
              </THead>
              <tbody>
                {log.rows.map((r) => (
                  <Tr key={r.id} className="align-top">
                    <Td className="tabular whitespace-nowrap text-ink-2">{formatDateTime(r.createdAt, timezone)}</Td>
                    <Td className="font-medium whitespace-nowrap">{AUDIT_ACTIONS[r.action as keyof typeof AUDIT_ACTIONS] ?? r.action}</Td>
                    <Td className="whitespace-nowrap">{r.actor ? `${r.actor.firstName} ${r.actor.lastName}` : "System"}</Td>
                    <Td className="whitespace-nowrap">
                      {r.subject ? (
                        <Link href={`/admin/employees/${r.subject.id}`} className="hover:underline">
                          {r.subject.firstName} {r.subject.lastName}
                        </Link>
                      ) : (
                        <span className="text-ink-3">{r.entityType}</span>
                      )}
                    </Td>
                    <Td className="min-w-64">
                      <Changes before={r.before} after={r.after} timezone={timezone} />
                    </Td>
                    <Td className="max-w-72 text-sm text-ink-2">{r.reason ?? "—"}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
            <Pagination page={log.page} pageSize={log.pageSize} total={log.total} hrefFor={hrefFor} />
          </>
        )}
      </Card>
    </>
  );
}
