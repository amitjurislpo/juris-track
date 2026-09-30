"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import {
  assignTeamAction,
  createEmployeeAction,
  resetPasswordAction,
  setEmployeeActiveAction,
  updateEmployeeAction,
} from "@/app/actions/employees";
import { Button, ButtonLink } from "@/components/ui/button";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Checkbox, FormError, SelectInput, TextInput } from "@/components/ui/form";
import { Icon } from "@/components/ui/icons";
import { Modal } from "@/components/ui/modal";
import { useToast } from "@/components/ui/toast";
import { useActionCall, useActionForm } from "@/components/ui/use-action-form";
import type { Role } from "@/generated/prisma/enums";
import { ROLE_LABELS } from "@/lib/auth/roles";
import type { Option } from "./team-forms";

const ROLES: Role[] = ["EMPLOYEE", "MANAGER", "ADMIN"];

function TemporaryPassword({ value }: { value: string }) {
  const toast = useToast();
  return (
    <div className="rounded-xl border border-accent/30 bg-accent-soft px-4 py-3">
      <p className="text-xs font-semibold tracking-wide text-accent uppercase">Temporary password — shown once</p>
      <div className="mt-2 flex items-center gap-3">
        <code className="flex-1 rounded-md bg-surface px-3 py-2 font-mono text-base text-ink select-all">{value}</code>
        <Button
          size="sm"
          variant="secondary"
          onClick={() => {
            void navigator.clipboard?.writeText(value).then(() => toast.success("Copied to clipboard."));
          }}
        >
          Copy
        </Button>
      </div>
      <p className="mt-2 text-xs text-ink-2">Share it securely. The employee will be asked to set their own password after signing in.</p>
    </div>
  );
}

/** Dedicated create-employee flow. */
export function CreateEmployeeForm({ teams, defaultTeamId }: { teams: Option[]; defaultTeamId?: string }) {
  const [created, setCreated] = useState<{ id: string; name: string; email: string; temporaryPassword: string | null } | null>(null);
  const [setOwn, setSetOwn] = useState(false);
  const { submit, pending, errors, formError } = useActionForm(createEmployeeAction, { onSuccess: setCreated, refresh: false });

  if (created) {
    return (
      <Card>
        <CardHeader title="Employee created" description={`${created.name} · ${created.email}`} />
        <CardBody className="space-y-5">
          {created.temporaryPassword ? (
            <TemporaryPassword value={created.temporaryPassword} />
          ) : (
            <p className="text-sm text-ink-2">The password you set has been saved. The employee will be asked to change it after signing in.</p>
          )}
          <div className="flex flex-wrap gap-2">
            <ButtonLink href={`/admin/employees/${created.id}`}>View profile</ButtonLink>
            <Button variant="secondary" onClick={() => setCreated(null)}>
              Add another employee
            </Button>
            {defaultTeamId && (
              <ButtonLink href={`/admin/teams/${defaultTeamId}`} variant="ghost">
                Back to team
              </ButtonLink>
            )}
          </div>
        </CardBody>
      </Card>
    );
  }

  return (
    <Card>
      <form action={submit}>
        <CardBody className="space-y-6">
          <FormError message={formError} />
          <div className="grid gap-4 sm:grid-cols-2">
            <TextInput label="First name" name="firstName" required maxLength={60} autoComplete="off" error={errors.firstName} />
            <TextInput label="Last name" name="lastName" required maxLength={60} autoComplete="off" error={errors.lastName} />
            <TextInput label="Work email" name="email" type="email" required maxLength={254} autoComplete="off" error={errors.email} />
            <TextInput label="Employee ID" name="employeeCode" maxLength={32} placeholder="e.g. JL-1042" error={errors.employeeCode} hint="Optional. Must be unique." />
            <SelectInput label="Team" name="teamId" defaultValue={defaultTeamId ?? ""} error={errors.teamId}>
              <option value="">Unassigned</option>
              {teams.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </SelectInput>
            <SelectInput label="Role" name="role" defaultValue="EMPLOYEE" error={errors.role}>
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABELS[r]}
                </option>
              ))}
            </SelectInput>
          </div>
          <Checkbox name="isActive" defaultChecked label="Active" description="Inactive accounts cannot sign in or record time." />
          <div className="space-y-3 rounded-xl border border-border bg-surface-2 p-4">
            <p className="text-sm font-medium text-ink">Sign-in credentials</p>
            <Checkbox
              checked={setOwn}
              onChange={(e) => setSetOwn(e.target.checked)}
              label="Set an initial password myself"
              description="Otherwise a secure temporary password is generated and shown once."
            />
            {setOwn && (
              <TextInput label="Initial password" name="password" type="password" autoComplete="new-password" required error={errors.password} hint="At least 10 characters with upper- and lowercase letters and a number." />
            )}
          </div>
        </CardBody>
        <div className="flex justify-end gap-2 border-t border-border bg-surface-2 px-5 py-3">
          <ButtonLink href="/admin/employees" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" loading={pending}>
            Create employee
          </Button>
        </div>
      </form>
    </Card>
  );
}

interface EmployeeValues {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  employeeCode: string | null;
  role: Role;
  isActive: boolean;
  teamId: string | null;
}

export function EmployeeAdminPanel({ employee, teams, isSelf }: { employee: EmployeeValues; teams: Option[]; isSelf: boolean }) {
  const [editing, setEditing] = useState(false);
  const [confirm, setConfirm] = useState<"deactivate" | "reset" | null>(null);
  const [tempPassword, setTempPassword] = useState<string | null>(null);
  const [teamId, setTeamId] = useState(employee.teamId ?? "");
  const { call, pending } = useActionCall();
  const edit = useActionForm(updateEmployeeAction, { onSuccess: () => setEditing(false) });
  const router = useRouter();

  return (
    <Card>
      <CardHeader
        title="Administration"
        actions={
          <>
            <Button size="sm" variant="secondary" onClick={() => setEditing(true)}>
              <Icon name="edit" className="size-4" /> Edit details
            </Button>
            <Button size="sm" variant="secondary" onClick={() => setConfirm("reset")}>
              <Icon name="key" className="size-4" /> Reset password
            </Button>
            {employee.isActive ? (
              !isSelf && (
                <Button size="sm" variant="ghost" className="text-danger" onClick={() => setConfirm("deactivate")}>
                  Deactivate
                </Button>
              )
            ) : (
              <Button size="sm" variant="success" loading={pending} onClick={() => call(() => setEmployeeActiveAction({ id: employee.id, active: true }))}>
                Reactivate
              </Button>
            )}
          </>
        }
      />
      <CardBody className="space-y-4">
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            call(() => assignTeamAction({ employeeId: employee.id, teamId: teamId || undefined }));
          }}
        >
          <SelectInput label="Team assignment" value={teamId} onChange={(e) => setTeamId(e.target.value)} fieldClassName="min-w-64" className="h-9">
            <option value="">Unassigned</option>
            {teams.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </SelectInput>
          <Button type="submit" size="sm" className="h-9" disabled={teamId === (employee.teamId ?? "")} loading={pending}>
            Save assignment
          </Button>
          <p className="basis-full text-xs text-ink-3">Moving an employee keeps their history; past workdays stay attributed to the team they were in.</p>
        </form>
        {tempPassword && <TemporaryPassword value={tempPassword} />}
        <p className="text-xs text-ink-3">
          All administrative changes are recorded in the <Link href={`/admin/audit-log?subjectId=${employee.id}`} className="underline">audit log</Link>.
        </p>
      </CardBody>

      <Modal open={editing} onClose={() => { edit.reset(); setEditing(false); }} title="Edit employee">
        <form action={edit.submit} className="space-y-4">
          <FormError message={edit.formError} />
          <input type="hidden" name="id" value={employee.id} />
          <div className="grid gap-4 sm:grid-cols-2">
            <TextInput label="First name" name="firstName" required defaultValue={employee.firstName} error={edit.errors.firstName} />
            <TextInput label="Last name" name="lastName" required defaultValue={employee.lastName} error={edit.errors.lastName} />
            <TextInput label="Work email" name="email" type="email" required defaultValue={employee.email} error={edit.errors.email} />
            <TextInput label="Employee ID" name="employeeCode" defaultValue={employee.employeeCode ?? ""} error={edit.errors.employeeCode} />
            <SelectInput label="Role" name="role" defaultValue={employee.role} error={edit.errors.role} disabled={isSelf} hint={isSelf ? "You cannot change your own role." : "Changing a role signs the user out."}>
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABELS[r]}
                </option>
              ))}
            </SelectInput>
            {isSelf && <input type="hidden" name="role" value={employee.role} />}
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setEditing(false)} disabled={edit.pending}>
              Cancel
            </Button>
            <Button type="submit" loading={edit.pending}>
              Save changes
            </Button>
          </div>
        </form>
      </Modal>

      <ConfirmDialog
        open={confirm === "deactivate"}
        onClose={() => setConfirm(null)}
        title={`Deactivate ${employee.firstName} ${employee.lastName}?`}
        description="They will be signed out and unable to sign in. If a workday is open, it will be ended now. Time history is preserved."
        confirmLabel="Deactivate"
        loading={pending}
        reason={{ label: "Reason", required: true }}
        onConfirm={(reason) => call(() => setEmployeeActiveAction({ id: employee.id, active: false, reason }), () => setConfirm(null))}
      />
      <ConfirmDialog
        open={confirm === "reset"}
        onClose={() => setConfirm(null)}
        title="Reset password?"
        description="A new temporary password will be generated and all of this user's sessions will be signed out."
        confirmLabel="Reset password"
        tone="primary"
        loading={pending}
        onConfirm={() =>
          call(
            () => resetPasswordAction(employee.id),
            (d) => {
              setTempPassword(d.temporaryPassword);
              setConfirm(null);
              router.refresh();
            },
          )
        }
      />
    </Card>
  );
}
