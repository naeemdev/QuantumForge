"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Check, ChevronDown, ChevronRight, CircleDashed, Download, Loader2, X } from "lucide-react";
import { TERMINAL_JOB_STATES, type JobArtifact, type JobAttempt, type JobDetail, type JobEvent, type TestCaseView } from "@quantumforge/contracts";
import { authorizedFetch } from "@/lib/api";
import { useJobEvents } from "@/lib/job-stream";
import { useAnalysisFindings, useCancelJob, useJob, useJobArtifacts, useJobTestResults, useProject } from "@/lib/queries";
import { FindingStatusBadge, SeverityBadge } from "@/components/findings";
import { useSession } from "@/lib/session";
import { formatBytes, formatDateTime, formatDuration, formatRelative } from "@/lib/format";
import { JobStateBadge } from "@/components/job-state";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardBody } from "@/components/ui/card";
import { PageHeader } from "@/components/ui/page-header";
import { EmptyState, ErrorState, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";
import { cn } from "@/components/ui/cn";

type Tab = "tests" | "findings" | "logs" | "environment" | "timeline" | "artifacts";

/** Plain statement of what the result does and does not show. */
function Verdict({ detail }: { detail: JobDetail }) {
  const { job } = detail;
  const tests = job.resultSummary?.tests as Record<string, number> | undefined;
  const analysis = job.resultSummary?.analysis as Record<string, number> | undefined;
  const text = (() => {
    if (job.jobType === "FUZZ_CAMPAIGN" && job.state === "SUCCEEDED") {
      const fuzz = job.resultSummary?.fuzz as Record<string, number> | undefined;
      return `Fuzz campaign completed: ${fuzz?.targetsTested ?? 0} functions tested with ${fuzz?.totalCalls ?? 0} calls, ${fuzz?.failures ?? 0} failures. See the campaign page for minimal inputs and reproduction tests.`;
    }
    if (job.jobType === "STATIC_ANALYSIS" && job.state === "SUCCEEDED") {
      return `Analysis completed: ${analysis?.symbols ?? 0} symbols indexed, ${analysis?.findings ?? 0} findings detected (${analysis?.newFindings ?? 0} new, ${analysis?.reopened ?? 0} reopened, ${analysis?.fixed ?? 0} no longer detected). Findings are suspected issues from pattern rules, not reproduced defects.`;
    }
    if (job.jobType !== "TEST_EXECUTION" && job.state === "FAILED") {
      return `The ${job.jobType === "FUZZ_CAMPAIGN" ? "campaign" : "analysis"} did not complete: ${job.failureReason ?? "see logs"}.`;
    }
    switch (job.state) {
      case "SUCCEEDED":
        return `All ${tests?.total ?? 0} executed tests passed on this snapshot. This shows the configured tests pass; it does not show the code is free of defects.`;
      case "FAILED":
        return tests && (tests.total ?? 0) > 0
          ? `${(tests.failed ?? 0) + (tests.errored ?? 0)} of ${tests.total} tests failed. Each failure below is reproducible by re-running this snapshot with the same configuration.`
          : `The run did not complete successfully: ${job.failureReason ?? "see logs"}.`;
      case "TIMED_OUT":
        return `Execution exceeded its ${job.timeoutSeconds}s limit and was stopped. Results are incomplete.`;
      case "INFRA_ERROR":
        return `The platform could not complete this run (${job.failureReason ?? "infrastructure error"}). This says nothing about the code under test.`;
      case "CANCELLED":
        return "The run was cancelled. Results, if any, are incomplete.";
      default:
        return "The run has not finished yet.";
    }
  })();
  const tone = job.jobType !== "TEST_EXECUTION" && job.state === "SUCCEEDED" ? "border-border bg-surface-2" : job.state === "SUCCEEDED" ? "border-success/30 bg-success-soft" : job.state === "FAILED" ? "border-danger/30 bg-danger-soft" : "border-border bg-surface-2";
  return <div className={cn("rounded-md border px-4 py-3 text-[13px] text-fg", tone)}>{text}</div>;
}

const phases = [
  { key: "job.queued", label: "Queued" },
  { key: "job.leased", label: "Worker assigned" },
  { key: "job.started", label: "Executing" },
  { key: "job.artifact", label: "Collecting evidence" },
  { key: "job.completed", label: "Finished" },
];

function Phases({ events, terminal }: { events: JobEvent[]; terminal: boolean }) {
  const seen = new Set(events.map((e) => e.eventType));
  if (terminal) seen.add("job.completed");
  const reached = phases.map((p) => seen.has(p.key));
  const current = reached.lastIndexOf(true);
  return (
    <ol className="flex flex-wrap items-center gap-x-2 gap-y-2">
      {phases.map((p, i) => {
        const done = i <= current && (terminal || i < current);
        const active = !terminal && i === current + 1;
        return (
          <li key={p.key} className="flex items-center gap-2 text-xs">
            <span className={cn("flex size-5 items-center justify-center rounded-full border", done ? "border-success bg-success-soft text-success" : active ? "border-accent text-accent" : "border-border text-fg-3")}>
              {done ? <Check className="size-3" /> : active ? <Loader2 className="size-3 animate-spin" /> : <CircleDashed className="size-3" />}
            </span>
            <span className={done || active ? "text-fg" : "text-fg-3"}>{p.label}</span>
            {i < phases.length - 1 && <span className="mx-1 h-px w-6 bg-border" aria-hidden />}
          </li>
        );
      })}
    </ol>
  );
}

function TestCaseRow({ c, initiallyOpen }: { c: TestCaseView; initiallyOpen: boolean }) {
  const [open, setOpen] = useState(initiallyOpen);
  const failed = c.status === "FAILED" || c.status === "ERRORED";
  return (
    <>
      <Tr className={failed ? "cursor-pointer" : undefined} onClick={() => failed && setOpen((o) => !o)}>
        <Td className="w-8 pr-0">{failed ? (open ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />) : null}</Td>
        <Td>
          <span className="text-fg">{c.name}</span>
          {c.suite && <span className="ml-2 text-xs text-fg-3">{c.suite}</span>}
        </Td>
        <Td>
          {c.status === "PASSED" && <Badge tone="success">Passed</Badge>}
          {c.status === "FAILED" && <Badge tone="danger">Failed</Badge>}
          {c.status === "ERRORED" && <Badge tone="warning">Error</Badge>}
          {c.status === "SKIPPED" && <Badge>Skipped</Badge>}
        </Td>
        <Td className="tabular text-right text-xs">{formatDuration(c.durationMs)}</Td>
      </Tr>
      {failed && open && (
        <tr>
          <td colSpan={4} className="border-b border-border bg-surface-2 px-4 py-3">
            {c.failureMessage && <p className="mb-2 font-mono text-xs text-danger">{c.failureMessage}</p>}
            {c.failureDetail && <pre className="max-h-72 overflow-auto whitespace-pre-wrap font-mono text-[11px] leading-5 text-fg-2">{c.failureDetail}</pre>}
          </td>
        </tr>
      )}
    </>
  );
}

function TestsTab({ jobId, finished }: { jobId: string; finished: boolean }) {
  const results = useJobTestResults(jobId, finished);
  const [onlyFailures, setOnlyFailures] = useState(false);
  if (!finished) return <EmptyState title="Waiting for results" description="Test results are recorded when the worker reports completion." />;
  if (results.isPending) return <Spinner />;
  if (results.isError) return <div className="p-4"><ErrorState error={results.error} /></div>;
  const run = results.data[results.data.length - 1];
  if (!run) return <EmptyState title="No test report" description="The run produced no parseable test report. Check the logs." />;
  // Open the first failure so the most relevant evidence is visible without a click.
  const firstFailure = run.cases.find((c) => c.status === "FAILED" || c.status === "ERRORED")?.ordinal;
  const cases = onlyFailures ? run.cases.filter((c) => c.status === "FAILED" || c.status === "ERRORED") : run.cases;
  return (
    <>
      <div className="flex items-center justify-between border-b border-border px-4 py-2 text-xs">
        <span className="tabular text-fg-2">
          {run.total} tests · <span className="text-success">{run.passed} passed</span> · <span className="text-danger">{run.failed} failed</span>
          {run.errored > 0 && <> · <span className="text-warning">{run.errored} errored</span></>}
          {run.skipped > 0 && <> · {run.skipped} skipped</>} · {run.framework}
        </span>
        <label className="flex items-center gap-1.5 text-fg-2">
          <input type="checkbox" checked={onlyFailures} onChange={(e) => setOnlyFailures(e.target.checked)} /> Failures only
        </label>
      </div>
      <Table>
        <thead>
          <tr>
            <Th className="w-8" />
            <Th>Test</Th>
            <Th>Result</Th>
            <Th className="text-right">Duration</Th>
          </tr>
        </thead>
        <tbody>{cases.map((c) => <TestCaseRow key={c.ordinal} c={c} initiallyOpen={c.ordinal === firstFailure} />)}</tbody>
      </Table>
    </>
  );
}

function FindingsTab({ jobId, finished }: { jobId: string; finished: boolean }) {
  const findings = useAnalysisFindings(jobId, finished);
  if (!finished) return <EmptyState title="Waiting for results" description="Findings are recorded when the analysis completes." />;
  if (findings.isPending) return <Spinner />;
  if (findings.isError) return <div className="p-4"><ErrorState error={findings.error} /></div>;
  if (findings.data.items.length === 0) return <EmptyState title="No findings" description="No rule matched this snapshot. That does not mean the code is free of defects." />;
  return (
    <Table>
      <thead><tr><Th>Severity</Th><Th>Finding</Th><Th>Location</Th><Th>Current status</Th></tr></thead>
      <tbody>
        {findings.data.items.map((f) => (
          <Tr key={f.findingId}>
            <Td><SeverityBadge severity={f.severity} /></Td>
            <Td className="max-w-md">
              <Link href={`/findings/${f.findingId}`} className="block truncate text-fg hover:underline">{f.message}</Link>
              <p className="font-mono text-xs text-fg-3">{f.ruleId} v{f.ruleVersion}</p>
            </Td>
            <Td className="font-mono text-xs">{f.path}:{f.startLine}</Td>
            <Td><FindingStatusBadge status={f.status} /></Td>
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}

function ArtifactText({ jobId, artifact }: { jobId: string; artifact: JobArtifact | undefined }) {
  const text = useQuery({
    queryKey: ["artifact-text", artifact?.id],
    enabled: artifact != null,
    staleTime: Infinity,
    queryFn: async () => (await authorizedFetch(`/api/v1/jobs/${jobId}/artifacts/${artifact!.id}/content`)).text(),
  });
  if (!artifact) return <p className="px-4 py-3 text-[13px] text-fg-3">Not captured yet.</p>;
  if (text.isPending) return <Spinner />;
  if (text.isError) return <div className="p-4"><ErrorState error={text.error} /></div>;
  return (
    <>
      {artifact.truncated && <p className="border-b border-border bg-warning-soft px-4 py-1.5 text-xs text-warning">Output exceeded the capture limit and was truncated.</p>}
      <pre className="max-h-[32rem] overflow-auto bg-surface-2 px-4 py-3 font-mono text-[11px] leading-5 text-fg">{text.data || <span className="text-fg-3">(empty)</span>}</pre>
    </>
  );
}

function LogsTab({ jobId, artifacts }: { jobId: string; artifacts: JobArtifact[] }) {
  const [stream, setStream] = useState<"STDOUT" | "STDERR">("STDOUT");
  const latest = (kind: string) => artifacts.filter((a) => a.kind === kind).at(-1);
  return (
    <>
      <div className="flex gap-1 border-b border-border px-3 py-2">
        {(["STDOUT", "STDERR"] as const).map((s) => (
          <button key={s} onClick={() => setStream(s)} className={cn("rounded px-2 py-1 text-xs", stream === s ? "bg-surface-2 font-medium text-fg" : "text-fg-3 hover:text-fg")}>
            {s.toLowerCase()} {latest(s) && <span className="text-fg-3">({formatBytes(latest(s)!.sizeBytes)})</span>}
          </button>
        ))}
      </div>
      <ArtifactText jobId={jobId} artifact={latest(stream)} />
    </>
  );
}

function KeyValues({ data }: { data: Record<string, unknown> }) {
  return (
    <dl className="divide-y divide-border">
      {Object.entries(data).map(([k, v]) => (
        <div key={k} className="grid grid-cols-[12rem_1fr] gap-4 px-4 py-2 text-[13px]">
          <dt className="text-fg-3">{k}</dt>
          <dd className="min-w-0 break-words text-fg-2">
            {v != null && typeof v === "object" ? (
              Array.isArray(v) ? <span className="font-mono text-xs">{v.join(", ")}</span> : (
                <dl className="space-y-1">
                  {Object.entries(v as Record<string, unknown>).map(([k2, v2]) => (
                    <div key={k2} className="flex gap-2">
                      <dt className="shrink-0 text-fg-3">{k2}:</dt>
                      <dd className="font-mono text-xs">{typeof v2 === "object" ? JSON.stringify(v2) : String(v2)}</dd>
                    </div>
                  ))}
                </dl>
              )
            ) : <span className="font-mono text-xs">{String(v ?? "—")}</span>}
          </dd>
        </div>
      ))}
    </dl>
  );
}

function EnvironmentTab({ attempts }: { attempts: JobAttempt[] }) {
  const attempt = attempts.at(-1);
  if (!attempt) return <EmptyState title="Not started" description="The environment is recorded when a worker starts the run." />;
  const isolation = (attempt.environment?.isolation ?? {}) as Record<string, unknown>;
  return (
    <div>
      {isolation.hostileCodeBoundary === false && (
        <p className="border-b border-border bg-warning-soft px-4 py-2 text-xs text-warning">
          This run used <span className="font-mono">{String(isolation.mode)}</span> isolation, which is for development and is not a security boundary for untrusted code.
        </p>
      )}
      <KeyValues
        data={{
          attempt: `${attempt.attemptNumber} on ${attempt.workerName}`,
          outcome: attempt.outcome ?? "in progress",
          exitCode: attempt.exitCode,
          duration: formatDuration(attempt.durationMs),
          ...(attempt.environment ?? {}),
        }}
      />
      {attempts.length > 1 && (
        <div className="border-t border-border">
          <p className="px-4 pt-3 text-xs font-medium text-fg-2">All attempts</p>
          <Table>
            <thead><tr><Th>#</Th><Th>Worker</Th><Th>Outcome</Th><Th>Error</Th><Th className="text-right">Leased</Th></tr></thead>
            <tbody>
              {attempts.map((a) => (
                <Tr key={a.id}>
                  <Td className="tabular">{a.attemptNumber}</Td>
                  <Td>{a.workerName} <span className="text-xs text-fg-3">({a.isolationMode})</span></Td>
                  <Td className="text-xs">{a.outcome ?? "running"}</Td>
                  <Td className="max-w-xs truncate text-xs">{a.errorMessage ?? "—"}</Td>
                  <Td className="text-right text-xs">{formatDateTime(a.leasedAt)}</Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        </div>
      )}
    </div>
  );
}

function TimelineTab({ events, live }: { events: JobEvent[]; live: boolean }) {
  return (
    <div>
      <p className="flex items-center gap-2 border-b border-border px-4 py-2 text-xs text-fg-3">
        <span className={cn("size-1.5 rounded-full", live ? "bg-success" : "bg-fg-3")} /> {live ? "Live" : "Not streaming"} · {events.length} events
      </p>
      <ol className="divide-y divide-border">
        {events.map((e) => (
          <li key={e.id} className="grid grid-cols-[10rem_12rem_1fr] gap-4 px-4 py-2 text-[13px]">
            <span className="tabular text-xs text-fg-3">{formatDateTime(e.occurredAt)}</span>
            <span className="font-mono text-xs text-fg-2">{e.eventType}</span>
            <span className="text-fg">{e.message}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}

function ArtifactsTab({ jobId, artifacts }: { jobId: string; artifacts: JobArtifact[] }) {
  async function download(a: JobArtifact) {
    const blob = await (await authorizedFetch(`/api/v1/jobs/${jobId}/artifacts/${a.id}/content`)).blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = a.name;
    link.click();
    URL.revokeObjectURL(url);
  }
  if (artifacts.length === 0) return <EmptyState title="No artifacts yet" />;
  return (
    <Table>
      <thead><tr><Th>Name</Th><Th>Kind</Th><Th>SHA-256</Th><Th className="text-right">Size</Th><Th /></tr></thead>
      <tbody>
        {artifacts.map((a) => (
          <Tr key={a.id}>
            <Td className="font-mono text-xs text-fg">{a.name}{a.truncated && <Badge tone="warning" className="ml-2">truncated</Badge>}</Td>
            <Td className="text-xs">{a.kind.toLowerCase().replace("_", " ")}</Td>
            <Td className="font-mono text-[11px]" title={a.sha256}>{a.sha256.slice(0, 16)}…</Td>
            <Td className="tabular text-right text-xs">{formatBytes(a.sizeBytes)}</Td>
            <Td className="text-right"><Button size="sm" variant="ghost" onClick={() => void download(a)}><Download className="size-3.5" /> Download</Button></Td>
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}

export default function JobPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const { can } = useSession();
  const detail = useJob(jobId);
  const { events, live } = useJobEvents(jobId);
  const project = useProject(detail.data?.job.projectId ?? "");
  const artifacts = useJobArtifacts(jobId, events.filter((e) => e.eventType === "job.artifact").length);
  const cancel = useCancelJob(jobId);

  const terminal = detail.data ? TERMINAL_JOB_STATES.includes(detail.data.job.state) : false;
  const jobType = detail.data?.job.jobType;
  const isAnalysis = jobType === "STATIC_ANALYSIS";
  const isFuzz = jobType === "FUZZ_CAMPAIGN";
  const [chosenTab, setTab] = useState<Tab | null>(null);
  const tab: Tab = chosenTab ?? (isAnalysis ? "findings" : isFuzz ? "logs" : "tests");
  const tabs = useMemo(
    () => [
      ...(isAnalysis ? [{ id: "findings" as const, label: "Findings" }] : isFuzz ? [] : [{ id: "tests" as const, label: "Tests" }]),
      { id: "logs" as const, label: "Logs" },
      { id: "environment" as const, label: "Environment" },
      { id: "timeline" as const, label: "Timeline" },
      { id: "artifacts" as const, label: `Artifacts${artifacts.data ? ` (${artifacts.data.length})` : ""}` },
    ],
    [artifacts.data, isAnalysis, isFuzz],
  );

  if (detail.isPending) return <Spinner />;
  if (detail.isError) return <ErrorState error={detail.error} />;
  const { job, attempts } = detail.data;

  return (
    <>
      <PageHeader
        breadcrumbs={[
          isAnalysis ? { label: "Code Intelligence", href: `/code-intelligence?project=${job.projectId}` }
            : isFuzz ? { label: "Break My Code", href: `/break-my-code/${job.id}` } : { label: "Test runs", href: "/test-runs" },
          ...(project.data ? [{ label: project.data.name, href: `/projects/${job.projectId}` }] : []),
          { label: job.id.slice(-8) },
        ]}
        title={<span className="flex items-center gap-3">{isAnalysis ? "Analysis" : isFuzz ? "Fuzz campaign" : "Test run"} <span className="font-mono text-base text-fg-2">{job.id.slice(-8)}</span> <JobStateBadge state={job.state} jobType={job.jobType} /></span>}
        actions={!terminal && can("job.cancel") && (
          <Button variant="danger" onClick={() => cancel.mutate()} loading={cancel.isPending} disabled={job.cancelRequested}>
            <X className="size-4" /> {job.cancelRequested ? "Cancelling…" : "Cancel run"}
          </Button>
        )}
      />

      <div className="space-y-4">
        <Verdict detail={detail.data} />
        <Card>
          <CardBody className="space-y-4">
            <Phases events={events} terminal={terminal} />
            <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-[13px] md:grid-cols-4">
              {[
                ["Snapshot", <Link key="s" href={`/projects/${job.projectId}/source?snapshot=${job.snapshotId}`} className="font-mono text-xs hover:underline">{job.snapshotId.slice(-12)}</Link>],
                ["Runner", job.config.preset === "NODE_TEST" ? "node:test" : job.config.preset === "TS_ANALYSIS" ? "TypeScript adapter" : job.config.preset === "TS_FUZZ" ? "TypeScript fuzzer" : job.config.preset],
                ["Queued", formatRelative(job.queuedAt)],
                ["Duration", formatDuration(job.resultSummary?.durationMs as number | undefined)],
                ["Attempts", `${job.attemptCount} of ${job.maxAttempts}`],
                ["Time limit", `${job.timeoutSeconds}s`],
                ["Memory", `${job.config.memoryMb} MB`],
                ["Idempotency key", <span key="k" className="font-mono text-xs">{job.idempotencyKey?.slice(0, 13) ?? "—"}</span>],
              ].map(([k, v]) => (
                <div key={String(k)}>
                  <dt className="text-xs text-fg-3">{k}</dt>
                  <dd className="mt-0.5 text-fg">{v}</dd>
                </div>
              ))}
            </dl>
          </CardBody>
        </Card>

        <Card>
          <div className="flex gap-1 border-b border-border px-2" role="tablist">
            {tabs.map((t) => (
              <button
                key={t.id}
                role="tab"
                aria-selected={tab === t.id}
                onClick={() => setTab(t.id)}
                className={cn("-mb-px border-b-2 px-3 py-2.5 text-[13px]", tab === t.id ? "border-accent font-medium text-fg" : "border-transparent text-fg-3 hover:text-fg")}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div role="tabpanel">
            {tab === "tests" && <TestsTab jobId={jobId} finished={terminal} />}
            {tab === "findings" && <FindingsTab jobId={jobId} finished={terminal} />}
            {tab === "logs" && <LogsTab jobId={jobId} artifacts={artifacts.data ?? []} />}
            {tab === "environment" && <EnvironmentTab attempts={attempts} />}
            {tab === "timeline" && <TimelineTab events={events} live={live} />}
            {tab === "artifacts" && <ArtifactsTab jobId={jobId} artifacts={artifacts.data ?? []} />}
          </div>
        </Card>
        {cancel.isError && <ErrorState error={cancel.error} />}
      </div>
    </>
  );
}
