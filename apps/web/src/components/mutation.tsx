"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { Dna, Play } from "lucide-react";
import { useMutationRuns, useSubmitMutationRun } from "@/lib/queries";
import { formatDuration, formatRelative } from "@/lib/format";
import { JobStateBadge } from "./job-state";
import { Badge, type Tone } from "./ui/badge";
import { Button } from "./ui/button";
import { Card, CardHeader } from "./ui/card";
import { Dialog } from "./ui/dialog";
import { Field, Input } from "./ui/input";
import { Stat } from "./ui/stat";
import { EmptyState, ErrorState, InlineError, Spinner } from "./ui/states";
import { Table, Td, Th, Tr } from "./ui/table";
import { cn } from "./ui/cn";

const statusTone: Record<string, { tone: Tone; label: string }> = {
  KILLED: { tone: "success", label: "Killed" },
  SURVIVED: { tone: "danger", label: "Survived" },
  TIMEOUT: { tone: "info", label: "Timed out" },
  ERROR: { tone: "warning", label: "Error" },
};

export function MutantStatusBadge({ status }: { status: string }) {
  const s = statusTone[status] ?? { tone: "neutral" as Tone, label: status };
  return <Badge tone={s.tone}>{s.label}</Badge>;
}

export function formatScore(score: number | null | undefined): string {
  return score == null ? "—" : `${score.toFixed(1)}%`;
}

/** A score bar: detected share of scored mutants. */
export function ScoreBar({ killed, timedOut, survived, className }: { killed: number; timedOut: number; survived: number; className?: string }) {
  const total = killed + timedOut + survived;
  if (total === 0) return null;
  const pct = (n: number) => `${(n / total) * 100}%`;
  return (
    <span className={cn("flex h-1.5 overflow-hidden rounded-full bg-surface-2", className)} role="img" aria-label={`${killed + timedOut} of ${total} mutants detected`}>
      <span className="bg-success" style={{ width: pct(killed) }} />
      <span className="bg-info" style={{ width: pct(timedOut) }} />
      <span className="bg-danger" style={{ width: pct(survived) }} />
    </span>
  );
}

export function MutationNote({ className }: { className?: string }) {
  return (
    <p className={cn("text-xs text-fg-3", className)}>
      Mutation testing makes small deliberate changes to your code and runs your tests against each one. A surviving mutant is a change no passing test
      noticed: usually a missing assertion, sometimes an equivalent change that cannot be detected. The score measures the tests, not the code.
    </p>
  );
}

function StartMutationRun({ projectId, snapshotId, open, onClose }: { projectId: string; snapshotId: string | undefined; open: boolean; onClose: () => void }) {
  const router = useRouter();
  const submit = useSubmitMutationRun(projectId);
  const [maxMutants, setMaxMutants] = useState(500);
  const [seed, setSeed] = useState(1);
  const [patterns, setPatterns] = useState("");

  async function start(e: React.FormEvent) {
    e.preventDefault();
    const testPatterns = patterns.split(/[\s,]+/).map((p) => p.trim()).filter(Boolean);
    const job = await submit.mutateAsync({ idempotencyKey: crypto.randomUUID(), snapshotId, seed, maxMutants, testPatterns });
    router.push(`/break-my-code/mutation/${job.id}`);
  }

  return (
    <Dialog open={open} onClose={onClose} title="Start a mutation run" description="Runs your node:test suite against every mutant in an isolated worker. Large projects are sampled up to the mutant limit.">
      <form onSubmit={(e) => void start(e)} className="space-y-4">
        <div className="grid grid-cols-2 gap-3">
          <Field label="Mutant limit" htmlFor="max-mutants" hint="Sampled by seed above this">
            <Input id="max-mutants" type="number" min={1} max={5000} required value={maxMutants} onChange={(e) => setMaxMutants(Number(e.target.value))} />
          </Field>
          <Field label="Seed" htmlFor="mutation-seed">
            <Input id="mutation-seed" type="number" min={1} max={2147483647} required value={seed} onChange={(e) => setSeed(Number(e.target.value))} />
          </Field>
        </div>
        <Field label="Test files" htmlFor="patterns" hint="Glob patterns; empty uses **/*.test.ts, **/*.test.js and **/*.test.mjs">
          <Input id="patterns" value={patterns} onChange={(e) => setPatterns(e.target.value)} placeholder="tests/**/*.test.ts" />
        </Field>
        <InlineError error={submit.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" loading={submit.isPending}><Play className="size-4" /> Start run</Button>
        </div>
      </form>
    </Dialog>
  );
}

export function MutationOverview({ projectId, snapshotId, canSubmit }: { projectId: string; snapshotId: string | undefined; canSubmit: boolean }) {
  const runs = useMutationRuns(projectId);
  const [starting, setStarting] = useState(false);
  const latest = runs.data?.items.find((r) => r.run != null)?.run;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Mutation score" value={formatScore(latest?.score)} detail={latest ? `${latest.killed + latest.timedOut} of ${latest.total - latest.errors} mutants detected` : "No run yet"} />
        <Stat label="Surviving mutants" value={latest?.survived ?? "—"} detail="Changes no passing test noticed" />
        <Stat label="Passing tests" value={latest ? latest.baseline.passed : "—"} detail={latest ? (latest.baseline.failed > 0 ? `${latest.baseline.failed} already failing, excluded` : "None failing") : undefined} />
        <Stat label="Last run" value={latest ? formatRelative(latest.createdAt) : "—"} detail={latest ? `${latest.total} mutants in ${formatDuration(latest.durationMs)}` : undefined} />
      </div>
      <Card>
        <CardHeader
          title="Mutation runs"
          actions={canSubmit && <Button size="sm" variant="primary" onClick={() => setStarting(true)} disabled={!snapshotId}><Play className="size-3.5" /> New mutation run</Button>}
        />
        {runs.isPending ? <Spinner /> : runs.isError ? <div className="p-4"><ErrorState error={runs.error} /></div> : runs.data.items.length === 0 ? (
          <EmptyState icon={<Dna className="size-5" />} title="No mutation runs yet" description="A run tells you which changes to your code your tests would not notice." />
        ) : (
          <Table>
            <thead><tr><Th>Run</Th><Th>Status</Th><Th className="w-56">Score</Th><Th>Mutants</Th><Th className="text-right">Duration</Th><Th className="text-right">Queued</Th></tr></thead>
            <tbody>
              {runs.data.items.map(({ job, run }) => (
                <Tr key={job.id}>
                  <Td><Link href={`/break-my-code/mutation/${job.id}`} className="font-mono text-xs text-fg hover:underline">{job.id.slice(-8)}</Link></Td>
                  <Td><JobStateBadge state={job.state} jobType={job.jobType} /></Td>
                  <Td>
                    {run ? run.notRunReason ? <span className="text-xs text-fg-3">Not run</span> : (
                      <div className="flex items-center gap-3">
                        <span className="tabular w-12 text-[13px] font-medium text-fg">{formatScore(run.score)}</span>
                        <ScoreBar killed={run.killed} timedOut={run.timedOut} survived={run.survived} className="flex-1" />
                      </div>
                    ) : "—"}
                  </Td>
                  <Td className="text-xs">{run ? <>{run.total} · <span className={run.survived > 0 ? "font-medium text-danger" : undefined}>{run.survived} survived</span></> : "—"}</Td>
                  <Td className="tabular text-right text-xs">{formatDuration(job.resultSummary?.durationMs as number | undefined)}</Td>
                  <Td className="text-right text-xs">{formatRelative(job.queuedAt)}</Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      <MutationNote />
      <StartMutationRun projectId={projectId} snapshotId={snapshotId} open={starting} onClose={() => setStarting(false)} />
    </div>
  );
}
