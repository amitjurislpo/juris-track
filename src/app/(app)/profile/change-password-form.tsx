"use client";

import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";
import { changePasswordAction } from "@/app/actions/profile";
import { Button } from "@/components/ui/button";
import { FormError, TextInput } from "@/components/ui/form";
import { useToast } from "@/components/ui/toast";
import type { FieldErrors } from "@/lib/errors";

export function ChangePasswordForm() {
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const formRef = useRef<HTMLFormElement>(null);
  const toast = useToast();
  const router = useRouter();

  return (
    <form
      ref={formRef}
      className="space-y-4"
      action={(form) =>
        start(async () => {
          const res = await changePasswordAction(form);
          if (res.ok) {
            setErrors({});
            setFormError(null);
            formRef.current?.reset();
            toast.success(res.message ?? "Password updated.");
            router.refresh();
          } else {
            setErrors(res.fieldErrors ?? {});
            setFormError(res.fieldErrors ? null : res.error);
          }
        })
      }
    >
      <FormError message={formError} />
      <TextInput label="Current password" name="currentPassword" type="password" autoComplete="current-password" required error={errors.currentPassword} />
      <TextInput label="New password" name="newPassword" type="password" autoComplete="new-password" required error={errors.newPassword} />
      <TextInput label="Confirm new password" name="confirmPassword" type="password" autoComplete="new-password" required error={errors.confirmPassword} />
      <Button type="submit" loading={pending}>
        Update password
      </Button>
    </form>
  );
}
