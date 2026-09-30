/** Loading placeholder shown while a page's data streams in. */
export function PageSkeleton() {
  return (
    <div className="animate-pulse space-y-6" aria-busy="true" aria-label="Loading">
      <div className="h-8 w-64 rounded-md bg-surface-3" />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => (
          <div key={i} className="h-24 rounded-xl bg-surface-3" />
        ))}
      </div>
      <div className="h-80 rounded-xl bg-surface-3" />
    </div>
  );
}
