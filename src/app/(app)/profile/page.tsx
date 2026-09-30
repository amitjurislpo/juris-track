import type { Metadata } from "next";
import { Badge } from "@/components/ui/badge";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { Callout, DescriptionList, PageHeader } from "@/components/ui/misc";
import { requireUser } from "@/lib/auth/guards";
import { ROLE_LABELS } from "@/lib/auth/roles";
import { db } from "@/lib/db";
import { ChangePasswordForm } from "./change-password-form";

export const metadata: Metadata = { title: "Profile" };

export default async function ProfilePage() {
  const user = await requireUser();
  const details = await db.user.findUniqueOrThrow({
    where: { id: user.id },
    select: {
      createdAt: true,
      isDemo: true,
      memberships: {
        where: { endedAt: null },
        take: 1,
        select: { team: { select: { name: true, manager: { select: { firstName: true, lastName: true } } } } },
      },
    },
  });
  const team = details.memberships[0]?.team;

  return (
    <>
      <PageHeader title="Profile" description="Your account details and password." />
      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title="Account" actions={details.isDemo ? <Badge tone="accent">Demo account</Badge> : undefined} />
          <CardBody>
            <DescriptionList
              items={[
                { label: "Name", value: `${user.firstName} ${user.lastName}` },
                { label: "Email", value: user.email },
                { label: "Employee ID", value: user.employeeCode ?? "—" },
                { label: "Role", value: ROLE_LABELS[user.role] },
                { label: "Team", value: team?.name ?? "Unassigned" },
                { label: "Manager", value: team?.manager ? `${team.manager.firstName} ${team.manager.lastName}` : "—" },
              ]}
            />
            <p className="mt-6 text-xs text-ink-3">To change your name, email or team, contact an administrator.</p>
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Change password" description="At least 10 characters, with upper- and lowercase letters and a number." />
          <CardBody className="space-y-4">
            {user.mustChangePassword && <Callout tone="warning" title="You are using a temporary password" />}
            <ChangePasswordForm />
          </CardBody>
        </Card>
      </div>
    </>
  );
}
