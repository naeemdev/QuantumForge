import type { JobState } from "@quantumforge/contracts";
import { Badge, type Tone } from "./ui/badge";

const presentation: Record<JobState, { tone: Tone; label: string }> = {
  QUEUED: { tone: "neutral", label: "Queued" },
  LEASED: { tone: "info", label: "Preparing" },
  RUNNING: { tone: "info", label: "Running" },
  SUCCEEDED: { tone: "success", label: "Passed" },
  FAILED: { tone: "danger", label: "Failed" },
  TIMED_OUT: { tone: "warning", label: "Timed out" },
  CANCELLED: { tone: "neutral", label: "Cancelled" },
  INFRA_ERROR: { tone: "warning", label: "Infrastructure error" },
};

/**
 * "Passed" means the configured tests passed on this snapshot — not that the
 * code is defect-free. The job detail page states this next to the result.
 */
export function JobStateBadge({ state }: { state: JobState }) {
  const p = presentation[state];
  return (
    <Badge tone={p.tone} dot>
      {p.label}
    </Badge>
  );
}

export function TestCounts({ tests }: { tests: Record<string, unknown> | null | undefined }) {
  if (!tests) return <span className="text-fg-3">—</span>;
  const n = (k: string) => (typeof tests[k] === "number" ? (tests[k] as number) : 0);
  return (
    <span className="tabular inline-flex items-center gap-2 text-xs">
      <span className="text-success">{n("passed")} passed</span>
      {n("failed") > 0 && <span className="text-danger">{n("failed")} failed</span>}
      {n("errored") > 0 && <span className="text-warning">{n("errored")} errored</span>}
      {n("skipped") > 0 && <span className="text-fg-3">{n("skipped")} skipped</span>}
    </span>
  );
}
