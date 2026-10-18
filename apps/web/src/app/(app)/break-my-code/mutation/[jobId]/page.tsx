"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useMemo, useState } from "react";
import { X } from "lucide-react";
import { TERMINAL_JOB_STATES, type MutantView, type MutationRunDetail } from "@quantumforge/contracts";
import { useCancelJob, useMutationRun, useProject } from "@/lib/queries";
import { useSession } from "@/lib/session";
import { formatDuration, formatRelative } from "@/lib/format";
import { MutantStatusBadge, MutationNote, ScoreBar, formatScore } from "@/components/mutation";
import { JobStateBadge } from "@/components/job-state";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { Select } from "@/components/ui/input";
import { PageHeader } from "@/components/ui/page-header";
import { Stat } from "@/components/ui/stat";
import { EmptyState, ErrorState, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";
import { cn } from "@/components/ui/cn";

function Verdict({ detail }: { detail: MutationRunDetail }) {
  const { job, run } = detail;
  let text: string;
  let tone = "border-border bg-surface-2";
  if (job.state === "SUCCEEDED" && run) {
    if (run.notRunReason) {
      text = `No mutants were run: ${run.notRunReason}`;
      tone = "border-warning/30 bg-warning-soft";
    } else {
      const scored = run.total - run.errors;
      text = `${run.killed + run.timedOut} of ${scored} mutants were detected by the ${run.baseline.passed} passing tests (score ${formatScore(run.score)}). `
        + (run.survived > 0 ? `${run.survived} survived: each is a change to the code that no passing test noticed.` : "Every mutant was detected.");
      if (run.baseline.failed > 0) text += ` ${run.baseline.failed} tests already fail on the unmutated code and were not used.`;
      if (run.survived > 0) tone = "border-danger/30 bg-danger-soft";
    }
  } else if (TERMINAL_JOB_STATES.includes(job.state)) {
    text = job.state === "INFRA_ERROR"
      ? `The platform could not complete this run (${job.failureReason ?? "infrastructure error"}). This says nothing about the code or its tests.`
      : `The run did not complete: ${job.failureReason ?? job.state.toLowerCase().replace("_", " ")}.`;
  } else {
    text = "The run has not finished yet. Every mutant runs the whole test suite, so this can take a few minutes.";
  }
  return <div className={cn("rounded-md border px-4 py-3 text-[13px] text-fg", tone)}>{text}</div>;
}

function Change({ m }: { m: MutantView }) {
  return (
    <code className="font-mono text-xs">
      <span className="rounded bg-danger-soft px-1 text-danger line-through decoration-danger/50">{m.original || " "}</span>
      <span className="mx-1.5 text-fg-3">→</span>
      <span className="rounded bg-success-soft px-1 text-success">{m.replacement || "(removed)"}</span>
    </code>
  );
}

function sourceLink(projectId: string, snapshotId: string, m: MutantView) {
  return `/projects/${projectId}/source?snapshot=${snapshotId}&path=${encodeURIComponent(m.path)}&line=${m.line}`;
}

export default function MutationRunPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const { can } = useSession();
  const detail = useMutationRun(jobId);
  const project = useProject(detail.data?.job.projectId ?? "");
  const cancel = useCancelJob(jobId);
  const [status, setStatus] = useState("");

  const mutants = detail.data?.mutants;
  const byFile = useMemo(() => {
    const files = new Map<string, { total: number; killed: number; survived: number; timedOut: number }>();
    for (const m of mutants ?? []) {
      const f = files.get(m.path) ?? { total: 0, killed: 0, survived: 0, timedOut: 0 };
      f.total++;
      if (m.status === "KILLED") f.killed++;
      if (m.status === "SURVIVED") f.survived++;
      if (m.status === "TIMEOUT") f.timedOut++;
      files.set(m.path, f);
    }
    return [...files.entries()].sort((a, b) => b[1].survived - a[1].survived || a[0].localeCompare(b[0]));
  }, [mutants]);

  if (detail.isPending) return <Spinner />;
  if (detail.isError) return <ErrorState error={detail.error} />;
  const { job, run } = detail.data;
  const all = detail.data.mutants;
  const terminal = TERMINAL_JOB_STATES.includes(job.state);
  const survivors = all.filter((m) => m.status === "SURVIVED");
  const shown = status ? all.filter((m) => m.status === status) : all;

  return (
    <>
      <PageHeader
        breadcrumbs={[
          { label: "Break My Code", href: `/break-my-code?project=${job.projectId}&tab=mutation` },
          ...(project.data ? [{ label: project.data.name, href: `/projects/${job.projectId}` }] : []),
          { label: job.id.slice(-8) },
        ]}
        title={<span className="flex items-center gap-3">Mutation run <span className="font-mono text-base text-fg-2">{job.id.slice(-8)}</span> <JobStateBadge state={job.state} jobType={job.jobType} /></span>}
        actions={
          <>
            <Link href={`/test-runs/${job.id}`} className="text-xs text-fg-2 hover:text-fg">Logs and evidence</Link>
            {!terminal && can("job.cancel") && (
              <Button variant="danger" onClick={() => cancel.mutate()} loading={cancel.isPending} disabled={job.cancelRequested}>
                <X className="size-4" /> {job.cancelRequested ? "Cancelling…" : "Cancel"}
              </Button>
            )}
          </>
        }
      />
      <div className="space-y-6">
        <Verdict detail={detail.data} />
        {run && !run.notRunReason && (
          <>
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
              <Stat label="Mutation score" value={formatScore(run.score)} detail={<ScoreBar killed={run.killed} timedOut={run.timedOut} survived={run.survived} className="mt-2" />} />
              <Stat label="Killed" value={run.killed} detail={run.timedOut > 0 ? `plus ${run.timedOut} timed out` : "by a passing test"} />
              <Stat label="Survived" value={run.survived} detail="No passing test noticed" />
              <Stat label="Mutants" value={run.total} detail={`${run.mutantsFound > run.total ? `sampled from ${run.mutantsFound} · ` : ""}${formatDuration(run.durationMs)}`} />
            </div>

            <section className="space-y-3">
              <h2 className="text-sm font-semibold text-fg">Surviving mutants</h2>
              {survivors.length === 0 ? <Card><EmptyState title="No survivors" description="Every mutant was detected by at least one passing test." /></Card> : (
                <Card>
                  <ul className="divide-y divide-border">
                    {survivors.map((m) => (
                      <li key={m.mutantId} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                        <div className="min-w-0 space-y-1">
                          <p className="flex flex-wrap items-center gap-2 text-[13px]">
                            <span className="font-mono text-xs text-fg">{m.path}:{m.line}</span>
                            {m.symbolKey && <span className="text-xs text-fg-3">in <span className="font-mono">{m.symbolKey.split("#")[1]}</span></span>}
                            <Badge>{m.operator}</Badge>
                          </p>
                          <Change m={m} />
                        </div>
                        <Link href={sourceLink(job.projectId, job.snapshotId, m)} className="shrink-0 text-xs text-fg-2 hover:text-fg">Open in source</Link>
                      </li>
                    ))}
                  </ul>
                </Card>
              )}
            </section>

            <Card>
              <CardHeader title="By file" />
              <Table>
                <thead><tr><Th>File</Th><Th className="w-64">Score</Th><Th>Mutants</Th><Th>Survived</Th></tr></thead>
                <tbody>
                  {byFile.map(([path, f]) => {
                    const score = Math.round(((f.killed + f.timedOut) / Math.max(1, f.total)) * 1000) / 10;
                    return (
                      <Tr key={path}>
                        <Td className="font-mono text-xs">{path}</Td>
                        <Td><div className="flex items-center gap-3"><span className="tabular w-12 text-xs">{formatScore(score)}</span><ScoreBar killed={f.killed} timedOut={f.timedOut} survived={f.survived} className="flex-1" /></div></Td>
                        <Td className="tabular text-xs">{f.total}</Td>
                        <Td className="tabular text-xs">{f.survived > 0 ? <span className="font-medium text-danger">{f.survived}</span> : 0}</Td>
                      </Tr>
                    );
                  })}
                </tbody>
              </Table>
            </Card>

            <Card>
              <CardHeader
                title="All mutants"
                actions={
                  <Select value={status} onChange={(e) => setStatus(e.target.value)} className="w-36" aria-label="Status">
                    <option value="">All ({all.length})</option>
                    {["SURVIVED", "KILLED", "TIMEOUT", "ERROR"].map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase().replace("timeout", "timed out")} ({all.filter((m) => m.status === s).length})</option>)}
                  </Select>
                }
              />
              <Table>
                <thead><tr><Th>Location</Th><Th>Change</Th><Th>Status</Th><Th>Detected by</Th><Th className="text-right">Time</Th></tr></thead>
                <tbody>
                  {shown.map((m) => (
                    <Tr key={m.mutantId}>
                      <Td className="whitespace-nowrap font-mono text-xs"><Link href={sourceLink(job.projectId, job.snapshotId, m)} className="hover:underline">{m.path}:{m.line}:{m.column}</Link></Td>
                      <Td className="max-w-xs"><Change m={m} /></Td>
                      <Td><MutantStatusBadge status={m.status} /></Td>
                      <Td className="max-w-xs truncate text-xs text-fg-2" title={m.killedBy ?? undefined}>{m.killedBy ?? "—"}</Td>
                      <Td className="tabular text-right text-xs">{formatDuration(m.durationMs)}</Td>
                    </Tr>
                  ))}
                </tbody>
              </Table>
            </Card>
          </>
        )}

        {run && (
          <Card>
            <CardHeader title="How this run worked" description={`Recorded ${formatRelative(run.createdAt)} · ${run.engineId} ${run.engineVersion} · seed ${run.seed}`} />
            <CardBody className="grid gap-6 text-[13px] md:grid-cols-2">
              <div className="space-y-4">
                <div>
                  <p className="text-xs font-medium text-fg">Test suite</p>
                  <p className="mt-1 text-fg-2">
                    {run.baseline.tests} tests on the unmutated code: {run.baseline.passed} passed, {run.baseline.failed} failed, in {formatDuration(run.baseline.durationMs)}.
                    Patterns: <span className="font-mono text-xs">{run.testPatterns.join(", ")}</span>
                  </p>
                  {run.baseline.failingTests.length > 0 && (
                    <details className="mt-2 text-xs">
                      <summary className="cursor-pointer text-fg-3 hover:text-fg">Already failing, not used ({run.baseline.failingTests.length})</summary>
                      <ul className="mt-2 space-y-1 font-mono text-fg-2">{run.baseline.failingTests.map((t) => <li key={t}>{t}</li>)}</ul>
                    </details>
                  )}
                </div>
                <div>
                  <p className="text-xs font-medium text-fg">Operators</p>
                  <ul className="mt-2 space-y-1.5 text-fg-2">{run.operators.map((o) => <li key={o.id}><span className="font-mono text-xs text-fg">{o.id}</span> {o.description}</li>)}</ul>
                </div>
              </div>
              <div>
                <p className="text-xs font-medium text-fg">Limitations</p>
                <ul className="mt-2 list-disc space-y-1 pl-4 text-fg-2">{run.limitations.map((l) => <li key={l}>{l}</li>)}</ul>
              </div>
            </CardBody>
          </Card>
        )}
        {!terminal && <Card><Spinner label="Mutation run in progress" /></Card>}
        <MutationNote />
      </div>
    </>
  );
}
