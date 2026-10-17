import type { ReactNode } from "react";
import { AlertTriangle, Loader2 } from "lucide-react";
import { ApiError } from "@/lib/api";

export function Spinner({ label = "Loading" }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 px-4 py-8 text-[13px] text-fg-3" role="status">
      <Loader2 className="size-4 animate-spin" aria-hidden />
      {label}
    </div>
  );
}

export function EmptyState({ icon, title, description, action }: { icon?: ReactNode; title: string; description?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-12 text-center">
      {icon && <div className="mb-3 text-fg-3">{icon}</div>}
      <p className="text-[13px] font-medium text-fg">{title}</p>
      {description && <p className="mt-1 max-w-sm text-[13px] text-fg-3">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : "Something went wrong";
  const requestId = error instanceof ApiError ? error.requestId : null;
  return (
    <div className="flex items-start gap-3 rounded-md border border-danger/30 bg-danger-soft px-4 py-3 text-[13px]" role="alert">
      <AlertTriangle className="mt-0.5 size-4 shrink-0 text-danger" aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="font-medium text-danger">{message}</p>
        {requestId && <p className="mt-0.5 font-mono text-xs text-fg-3">request {requestId}</p>}
      </div>
      {onRetry && (
        <button onClick={onRetry} className="text-xs font-medium text-danger underline-offset-2 hover:underline">
          Retry
        </button>
      )}
    </div>
  );
}

export function InlineError({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  return <p className="text-xs text-danger">{message}</p>;
}
