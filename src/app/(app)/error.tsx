"use client";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/misc";

export default function AppError({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <Card>
      <EmptyState
        icon="alert"
        title="This page could not be loaded"
        description="Your time records are safe. Try again, and contact an administrator if the problem continues."
        action={<Button onClick={reset}>Try again</Button>}
      />
    </Card>
  );
}
