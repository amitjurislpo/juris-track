"use client";

import { useState } from "react";
import { updateSettingsAction } from "@/app/actions/admin-time";
import { Button } from "@/components/ui/button";
import { Field, FormError, SelectInput, TextInput } from "@/components/ui/form";
import { Icon } from "@/components/ui/icons";
import { useActionForm } from "@/components/ui/use-action-form";
import { zoneLabel } from "@/lib/time/zones";

/** Editable list of timezones viewers can switch the display to. */
function DisplayZonesField({ initial, all, error }: { initial: string[]; all: string[]; error?: string[] }) {
  const [zones, setZones] = useState(initial);
  const [pick, setPick] = useState("");
  const available = all.filter((tz) => !zones.includes(tz));

  return (
    <Field
      label="Display timezones"
      error={error}
      hint="Everyone can switch between these from the sidebar. Only how times are shown changes; the organization timezone above is always included."
    >
      {zones.map((tz) => (
        <input key={tz} type="hidden" name="displayTimezones" value={tz} />
      ))}
      <ul className="divide-y divide-border rounded-lg border border-border">
        {zones.map((tz) => (
          <li key={tz} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
            <span>
              <span className="font-medium text-ink">{zoneLabel(tz)}</span>
              <span className="ml-2 text-xs text-ink-3">{tz}</span>
            </span>
            <button
              type="button"
              onClick={() => setZones(zones.filter((z) => z !== tz))}
              className="rounded-md p-1 text-ink-3 hover:bg-surface-3 hover:text-danger"
              aria-label={`Remove ${tz}`}
            >
              <Icon name="x" className="size-4" />
            </button>
          </li>
        ))}
        {zones.length === 0 && <li className="px-3 py-2 text-sm text-ink-3">Only the organization timezone will be shown.</li>}
      </ul>
      <div className="flex gap-2">
        <SelectInput aria-label="Add a timezone" value={pick} onChange={(e) => setPick(e.target.value)} className="h-9">
          <option value="">Add a timezone…</option>
          {available.map((tz) => (
            <option key={tz} value={tz}>
              {tz}
            </option>
          ))}
        </SelectInput>
        <Button
          size="sm"
          variant="secondary"
          className="h-9"
          disabled={!pick}
          onClick={() => {
            setZones([...zones, pick]);
            setPick("");
          }}
        >
          Add
        </Button>
      </div>
    </Field>
  );
}

export function SettingsForm({
  values,
  timezones,
}: {
  values: { organizationName: string; timezone: string; staleWorkdayHours: number; displayTimezones: string[] };
  timezones: string[];
}) {
  const { submit, pending, errors, formError } = useActionForm(updateSettingsAction);
  return (
    <form action={submit} className="max-w-xl space-y-5">
      <FormError message={formError} />
      <TextInput label="Organization name" name="organizationName" required maxLength={80} defaultValue={values.organizationName} error={errors.organizationName} />
      <SelectInput
        label="Organization timezone"
        name="timezone"
        defaultValue={values.timezone}
        error={errors.timezone}
        hint="Defines the calendar day each workday belongs to and the default display. Changing it affects new workdays only."
      >
        {timezones.map((tz) => (
          <option key={tz} value={tz}>
            {tz}
          </option>
        ))}
      </SelectInput>
      <DisplayZonesField
        initial={values.displayTimezones.filter((tz) => tz !== values.timezone)}
        all={timezones}
        error={errors.displayTimezones ?? errors["displayTimezones.0"]}
      />
      <TextInput
        label="Flag open workdays after (hours)"
        name="staleWorkdayHours"
        type="number"
        min={1}
        max={48}
        required
        defaultValue={values.staleWorkdayHours}
        error={errors.staleWorkdayHours}
        hint="Workdays still open after this long are flagged as 'Not ended' for review. Crossing midnight alone is not flagged, so night shifts are supported."
      />
      <Button type="submit" loading={pending}>
        Save settings
      </Button>
    </form>
  );
}
