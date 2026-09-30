"use client";

import { useState } from "react";
import { adjustSessionAction, closeWorkdayAction } from "@/app/actions/admin-time";
import { Button } from "@/components/ui/button";
import { FormError, TextArea, TextInput } from "@/components/ui/form";
import { Icon } from "@/components/ui/icons";
import { Modal } from "@/components/ui/modal";
import { useActionForm } from "@/components/ui/use-action-form";
import { toZonedInputValue } from "@/lib/time/tz";
import { zoneLabel } from "@/lib/time/zones";

export function AdjustIntervalButton({
  kind,
  sessionId,
  startedAt,
  endedAt,
  timezone,
}: {
  kind: "WORK" | "BREAK";
  sessionId: string;
  startedAt: string;
  endedAt: string | null;
  timezone: string;
}) {
  const [open, setOpen] = useState(false);
  const { submit, pending, errors, formError, reset } = useActionForm(adjustSessionAction, { onSuccess: () => setOpen(false) });
  const close = () => {
    reset();
    setOpen(false);
  };
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-ink-2 hover:bg-surface-3 hover:text-ink"
      >
        <Icon name="edit" className="size-3.5" /> Adjust
      </button>
      <Modal
        open={open}
        onClose={close}
        title={`Adjust ${kind === "WORK" ? "work" : "break"} interval`}
        description={`Times are in ${zoneLabel(timezone)}. The change, previous values and your reason are recorded in the audit log.`}
      >
        <form action={submit} className="space-y-4">
          <FormError message={formError} />
          <input type="hidden" name="kind" value={kind} />
          <input type="hidden" name="sessionId" value={sessionId} />
          <input type="hidden" name="timezone" value={timezone} />
          <div className="grid gap-4 sm:grid-cols-2">
            <TextInput
              label="Start"
              name="startedAt"
              type="datetime-local"
              required
              defaultValue={toZonedInputValue(startedAt, timezone)}
              error={errors.startedAt}
            />
            {endedAt ? (
              <TextInput label="End" name="endedAt" type="datetime-local" required defaultValue={toZonedInputValue(endedAt, timezone)} error={errors.endedAt} />
            ) : (
              <TextInput label="End" disabled value="In progress" hint="Close the workday to end a running interval." />
            )}
          </div>
          <TextArea label="Reason for adjustment" name="reason" required minLength={5} maxLength={500} error={errors.reason} placeholder="e.g. Employee forgot to take a break in the system; confirmed with manager." />
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={close} disabled={pending}>
              Cancel
            </Button>
            <Button type="submit" loading={pending}>
              Save adjustment
            </Button>
          </div>
        </form>
      </Modal>
    </>
  );
}

export function CloseWorkdayButton({ workdayId, timezone, defaultEnd }: { workdayId: string; timezone: string; defaultEnd?: string }) {
  const [open, setOpen] = useState(false);
  const { submit, pending, errors, formError, reset } = useActionForm(closeWorkdayAction, { onSuccess: () => setOpen(false) });
  const close = () => {
    reset();
    setOpen(false);
  };
  return (
    <>
      <Button size="sm" variant="secondary" onClick={() => setOpen(true)}>
        Close workday
      </Button>
      <Modal
        open={open}
        onClose={close}
        title="Close open workday"
        description="Ends the running work or break interval at the time you choose and completes the workday. This is recorded in the audit log."
      >
        <form action={submit} className="space-y-4">
          <FormError message={formError} />
          <input type="hidden" name="workdayId" value={workdayId} />
          <input type="hidden" name="timezone" value={timezone} />
          <TextInput
            label="End time"
            name="endAt"
            type="datetime-local"
            defaultValue={defaultEnd ? toZonedInputValue(defaultEnd, timezone) : undefined}
            hint={`Leave empty to end now. Times are in ${zoneLabel(timezone)}.`}
            error={errors.endAt}
          />
          <TextArea label="Reason" name="reason" required minLength={5} maxLength={500} error={errors.reason} placeholder="e.g. Employee did not end their workday; confirmed they left at 18:05." />
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={close} disabled={pending}>
              Cancel
            </Button>
            <Button type="submit" loading={pending}>
              Close workday
            </Button>
          </div>
        </form>
      </Modal>
    </>
  );
}
