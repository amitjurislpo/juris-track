"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { assignTeamAction } from "@/app/actions/employees";
import { createTeamAction, deleteTeamAction, setTeamActiveAction, updateTeamAction } from "@/app/actions/teams";
import { Button, ButtonLink } from "@/components/ui/button";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { FormError, SelectInput, TextArea, TextInput } from "@/components/ui/form";
import { Icon } from "@/components/ui/icons";
import { Modal } from "@/components/ui/modal";
import { useActionCall, useActionForm } from "@/components/ui/use-action-form";

export interface Option {
  value: string;
  label: string;
  hint?: string;
}

interface TeamValues {
  id?: string;
  name: string;
  description: string | null;
  managerId: string | null;
}

function TeamForm({ team, managers, onDone }: { team?: TeamValues; managers: Option[]; onDone: (id?: string) => void }) {
  const { submit, pending, errors, formError } = useActionForm<{ id: string; name: string } | null>(team ? updateTeamAction : createTeamAction, {
    onSuccess: (data) => onDone(data?.id),
  });
  return (
    <form action={submit} className="space-y-4">
      <FormError message={formError} />
      {team?.id && <input type="hidden" name="id" value={team.id} />}
      <TextInput label="Team name" name="name" required maxLength={80} defaultValue={team?.name} error={errors.name} placeholder="e.g. Litigation Support" autoFocus />
      <TextArea label="Description" name="description" maxLength={500} defaultValue={team?.description ?? ""} error={errors.description} />
      <SelectInput label="Manager" name="managerId" defaultValue={team?.managerId ?? ""} error={errors.managerId} hint="Managers see live status and reports for this team.">
        <option value="">No manager</option>
        {managers.map((m) => (
          <option key={m.value} value={m.value}>
            {m.label}
          </option>
        ))}
      </SelectInput>
      <div className="flex justify-end gap-2 pt-2">
        <Button variant="secondary" onClick={() => onDone()} disabled={pending}>
          Cancel
        </Button>
        <Button type="submit" loading={pending}>
          {team ? "Save team" : "Create team"}
        </Button>
      </div>
    </form>
  );
}

export function AddTeamButton({ managers }: { managers: Option[] }) {
  const [open, setOpen] = useState(false);
  const router = useRouter();
  return (
    <>
      <Button onClick={() => setOpen(true)}>
        <Icon name="plus" className="size-4" /> Add Team
      </Button>
      <Modal open={open} onClose={() => setOpen(false)} title="Add team" description="Teams are fully dynamic — create any team your organization needs.">
        <TeamForm
          managers={managers}
          onDone={(id) => {
            setOpen(false);
            if (id) router.push(`/admin/teams/${id}`);
          }}
        />
      </Modal>
    </>
  );
}

export function TeamAdminPanel({
  team,
  managers,
  candidates,
  canDelete,
}: {
  team: TeamValues & { id: string; isActive: boolean; memberCount: number };
  managers: Option[];
  candidates: Option[];
  canDelete: boolean;
}) {
  const [editing, setEditing] = useState(false);
  const [adding, setAdding] = useState(false);
  const [confirm, setConfirm] = useState<"deactivate" | "delete" | null>(null);
  const [selected, setSelected] = useState("");
  const { call, pending } = useActionCall();
  const router = useRouter();

  return (
    <Card>
      <CardHeader
        title="Team administration"
        actions={
          <>
            {team.isActive && (
              <Button size="sm" onClick={() => setAdding(true)}>
                <Icon name="plus" className="size-4" /> Add Employee
              </Button>
            )}
            <Button size="sm" variant="secondary" onClick={() => setEditing(true)}>
              <Icon name="edit" className="size-4" /> Edit
            </Button>
            {team.isActive ? (
              <Button size="sm" variant="secondary" onClick={() => setConfirm("deactivate")}>
                Deactivate
              </Button>
            ) : (
              <Button size="sm" variant="secondary" loading={pending} onClick={() => call(() => setTeamActiveAction({ id: team.id, active: true }))}>
                Reactivate
              </Button>
            )}
            {canDelete && (
              <Button size="sm" variant="ghost" className="text-danger" onClick={() => setConfirm("delete")}>
                Delete
              </Button>
            )}
          </>
        }
      />
      {!team.isActive && (
        <CardBody>
          <p className="text-sm text-ink-3">This team is inactive. Reactivate it to assign employees.</p>
        </CardBody>
      )}

      <Modal open={editing} onClose={() => setEditing(false)} title="Edit team">
        <TeamForm team={team} managers={managers} onDone={() => setEditing(false)} />
      </Modal>

      <Modal
        open={adding}
        onClose={() => setAdding(false)}
        title={`Add employee to ${team.name}`}
        description="Select an existing employee, or create a new one. An employee belongs to one team at a time; adding moves them here."
        footer={
          <>
            <ButtonLink href={`/admin/employees/new?teamId=${team.id}`} variant="ghost">
              Create new employee
            </ButtonLink>
            <Button
              disabled={!selected}
              loading={pending}
              onClick={() =>
                call(
                  () => assignTeamAction({ employeeId: selected, teamId: team.id }),
                  () => {
                    setSelected("");
                    setAdding(false);
                  },
                )
              }
            >
              Add to team
            </Button>
          </>
        }
      >
        {candidates.length === 0 ? (
          <p className="text-sm text-ink-3">Every active employee is already in this team.</p>
        ) : (
          <SelectInput label="Employee" value={selected} onChange={(e) => setSelected(e.target.value)}>
            <option value="">Select an employee…</option>
            {candidates.map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
                {c.hint ? ` — ${c.hint}` : ""}
              </option>
            ))}
          </SelectInput>
        )}
      </Modal>

      <ConfirmDialog
        open={confirm === "deactivate"}
        onClose={() => setConfirm(null)}
        title={`Deactivate ${team.name}?`}
        description={`${team.memberCount} member${team.memberCount === 1 ? "" : "s"} will be unassigned. Historical time records remain attributed to this team.`}
        confirmLabel="Deactivate team"
        loading={pending}
        reason={{ label: "Reason" }}
        onConfirm={(reason) => call(() => setTeamActiveAction({ id: team.id, active: false, reason: reason || undefined }), () => setConfirm(null))}
      />
      <ConfirmDialog
        open={confirm === "delete"}
        onClose={() => setConfirm(null)}
        title={`Delete ${team.name}?`}
        description="This team has no history and will be permanently deleted."
        confirmLabel="Delete team"
        loading={pending}
        onConfirm={() => call(() => deleteTeamAction(team.id), () => router.push("/admin/teams"))}
      />
    </Card>
  );
}

export function RemoveMemberButton({ employeeId, name, teamName }: { employeeId: string; name: string; teamName: string }) {
  const [open, setOpen] = useState(false);
  const { call, pending } = useActionCall();
  return (
    <>
      <Button size="sm" variant="ghost" onClick={() => setOpen(true)}>
        Remove
      </Button>
      <ConfirmDialog
        open={open}
        onClose={() => setOpen(false)}
        title={`Remove ${name} from ${teamName}?`}
        description="They will become unassigned. Their time records are not affected."
        confirmLabel="Remove"
        loading={pending}
        onConfirm={() => call(() => assignTeamAction({ employeeId }), () => setOpen(false))}
      />
    </>
  );
}
