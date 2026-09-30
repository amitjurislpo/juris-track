"use client";

import { useActionState } from "react";
import { loginAction, type LoginState } from "@/app/actions/auth";
import { Button } from "@/components/ui/button";
import { FormError, TextInput } from "@/components/ui/form";

export function LoginForm() {
  const [state, formAction, pending] = useActionState<LoginState, FormData>(loginAction, {});
  return (
    <form action={formAction} className="space-y-5" noValidate>
      <FormError message={state.error} />
      <TextInput
        label="Email"
        name="email"
        type="email"
        autoComplete="username"
        required
        autoFocus
        defaultValue={state.email}
        placeholder="name@jurislpo.com"
      />
      <TextInput label="Password" name="password" type="password" autoComplete="current-password" required />
      <Button type="submit" size="lg" className="w-full" loading={pending}>
        Sign in
      </Button>
    </form>
  );
}
