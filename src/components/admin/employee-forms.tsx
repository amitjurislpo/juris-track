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

/** Sign-in details for the admin to share with the user (password shown once). */
function SignInDetails({ email, password }: { email: string; password: string }) {
  const toast = useToast();
  const copy = (text: string, what: string) => void navigator.clipboard?.writeText(text).then(() => toast.success(`${what} copied.`));
  return (
    <div className="rounded-xl border border-accent/30 bg-accent-soft px-4 py-3">
      <p className="text-xs font-semibold tracking-wide text-accent uppercase">Sign-in details to share — password shown once</p>
      <dl className="mt-3 space-y-2 text-sm">
        {[
          { label: "Username", value: email },
          { label: "Password", value: password },
        ].map((row) => (
          <div key={row.label} className="flex items-center gap-3">
            <dt className="w-20 shrink-0 text-ink-2">{row.label}</dt>
            <dd className="flex-1">
              <code className="block rounded-md bg-surface px-3 py-1.5 font-mono text-ink select-all">{row.value}</code>
            </dd>
          </div>
        ))}
      </dl>
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <Button size="sm" variant="secondary" onClick={() => copy(`Username: ${email}\nPassword: ${password}`, "Sign-in details")}>
          Copy sign-in details
        </Button>
        <p className="text-xs text-ink-2">The user will be asked to change this password after signing in.</p>
      </div>
    </div>
  );
}

type PasswordMode = "default" | "generated" | "custom";

/** Dedicated create-user flow. */
export function CreateEmployeeForm({
  teams,
  defaultTeamId,
  defaultPassword,
}: {
  teams: Option[];
  defaultTeamId?: string;
  /** Organization default password, if configured. */
  defaultPassword: string | null;
}) {
  const [created, setCreated] = useState<{ id: string; name: string; email: string; temporaryPassword: string | null } | null>(null);
  const [mode, setMode] = useState<PasswordMode>(defaultPassword ? "default" : "generated");
  const { submit, pending, errors, formError } = useActionForm(createEmployeeAction, { onSuccess: setCreated, refresh: false });

  if (created) {
    return (
      <Card>
        <CardHeader title="User created" description={`${created.name} · ${created.email}`} />
        <CardBody className="space-y-5">
          {created.temporaryPassword ? (
            <SignInDetails email={created.email} password={created.temporaryPassword} />
          ) : (
            <p className="text-sm text-ink-2">
              The password you typed has been saved. Share it with <strong>{created.email}</strong> securely; they will be asked to
              change it after signing in.
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <ButtonLink href={`/admin/employees/${created.id}`}>View profile</ButtonLink>
            <Button variant="secondary" onClick={() => setCreated(null)}>
              Add another user
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

  const options: Array<{ value: PasswordMode; label: string; description: string; disabled?: boolean }> = [
    {
      value: "default",
      label: defaultPassword ? `Default password (${defaultPassword})` : "Default password",
      description: defaultPassword ? "The organization's standard first-login password." : "Not configured (set DEFAULT_USER_PASSWORD).",
      disabled: !defaultPassword,
    },
    { value: "generated", label: "Generate a unique password", description: "A random password, shown once for you to share." },
    { value: "custom", label: "Type a password", description: "Choose the initial password yourself." },
  ];

  return (
    <Card>
      <form action={submit}>
        <CardBody className="space-y-6">
          <FormError message={formError} />
          <div className="grid gap-4 sm:grid-cols-2">
            <TextInput label="First name" name="firstName" required maxLength={60} autoComplete="off" error={errors.firstName} />
            <TextInput label="Last name" name="lastName" required maxLength={60} autoComplete="off" error={errors.lastName} />
            <TextInput label="Work email (username)" name="email" type="email" required maxLength={254} autoComplete="off" error={errors.email} />
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
          <fieldset className="space-y-3 rounded-xl border border-border bg-surface-2 p-4">
            <legend className="px-1 text-sm font-medium text-ink">Initial password</legend>
            {options.map((o) => (
              <label key={o.value} className={`flex items-start gap-3 ${o.disabled ? "cursor-not-allowed opacity-50" : "cursor-pointer"}`}>
                <input
                  type="radio"
                  name="passwordMode"
                  value={o.value}
                  checked={mode === o.value}
                  disabled={o.disabled}
                  onChange={() => setMode(o.value)}
                  className="mt-1 size-4 accent-[var(--brand)]"
                />
                <span>
                  <span className="block text-sm font-medium text-ink">{o.label}</span>
                  <span className="block text-xs text-ink-3">{o.description}</span>
                </span>
              </label>
            ))}
            {mode === "custom" && (
              <TextInput
                label="Password"
                name="password"
                type="password"
                autoComplete="new-password"
                required
                error={errors.password}
                hint="At least 10 characters with upper- and lowercase letters and a number."
              />
            )}
            <p className="text-xs text-ink-3">Whichever you choose, the user is prompted to set their own password after signing in.</p>
          </fieldset>
        </CardBody>
        <div className="flex justify-end gap-2 border-t border-border bg-surface-2 px-5 py-3">
          <ButtonLink href="/admin/employees" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" loading={pending}>
            Create user
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

export function EmployeeAdminPanel({
  employee,
  teams,
  isSelf,
  defaultPassword,
}: {
  employee: EmployeeValues;
  teams: Option[];
  isSelf: boolean;
  defaultPassword: string | null;
}) {
  const resetDescription = defaultPassword
    ? `The password will be reset to the default (${defaultPassword}) and the user signed out everywhere. They'll be asked to change it at next sign-in.`
    : "A new temporary password will be generated and the user signed out everywhere.";
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
        {tempPassword && <SignInDetails email={employee.email} password={tempPassword} />}
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
        description={resetDescription}
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
