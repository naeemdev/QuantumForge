"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Bug, Play } from "lucide-react";
import { ACTIVE_FINDING_STATUSES } from "@quantumforge/contracts";
import { useFindings, useFuzzCampaigns, useRevisions, useSubmitFuzzCampaign } from "@/lib/queries";
import { useSession } from "@/lib/session";
import { formatDuration, formatRelative } from "@/lib/format";
import { EvidenceBadge, FuzzingNote } from "@/components/fuzzing";
import { FindingStatusBadge, SeverityBadge } from "@/components/findings";
import { MutationOverview } from "@/components/mutation";
import { JobStateBadge } from "@/components/job-state";
import { ProjectPicker, useSelectedProject } from "@/components/project-picker";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input } from "@/components/ui/input";
import { PageHeader } from "@/components/ui/page-header";
import { Stat } from "@/components/ui/stat";
import { EmptyState, ErrorState, InlineError, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";
import { cn } from "@/components/ui/cn";

type Tab = "fuzzing" | "mutation";

function StartCampaign({ projectId, snapshotId, open, onClose }: { projectId: string; snapshotId: string | undefined; open: boolean; onClose: () => void }) {
  const router = useRouter();
  const submit = useSubmitFuzzCampaign(projectId);
  const [seed, setSeed] = useState(1);
  const [iterations, setIterations] = useState(300);
  const [callTimeout, setCallTimeout] = useState(1000);

  async function start(e: React.FormEvent) {
    e.preventDefault();
    const job = await submit.mutateAsync({ idempotencyKey: crypto.randomUUID(), snapshotId, seed, iterationsPerTarget: iterations, callTimeoutMs: callTimeout });
    router.push(`/break-my-code/${job.id}`);
  }

  return (
    <Dialog open={open} onClose={onClose} title="Start a fuzz campaign" description="Runs in an isolated worker against the latest snapshot. Open fuzzing findings are replayed first.">
      <form onSubmit={(e) => void start(e)} className="space-y-4">
        <div className="grid grid-cols-3 gap-3">
          <Field label="Seed" htmlFor="seed" hint="Same seed, same inputs">
            <Input id="seed" type="number" min={1} max={2147483647} required value={seed} onChange={(e) => setSeed(Number(e.target.value))} />
          </Field>
          <Field label="Calls per function" htmlFor="iterations">
            <Input id="iterations" type="number" min={1} max={100000} required value={iterations} onChange={(e) => setIterations(Number(e.target.value))} />
          </Field>
          <Field label="Call limit (ms)" htmlFor="timeout">
            <Input id="timeout" type="number" min={50} max={60000} required value={callTimeout} onChange={(e) => setCallTimeout(Number(e.target.value))} />
          </Field>
        </div>
        <InlineError error={submit.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" loading={submit.isPending}><Play className="size-4" /> Start campaign</Button>
        </div>
      </form>
    </Dialog>
  );
}

function Defects({ projectId }: { projectId: string }) {
  const findings = useFindings(projectId, { source: "FUZZING", size: 100 });
  if (findings.isPending) return <Spinner />;
  if (findings.isError) return <div className="p-4"><ErrorState error={findings.error} /></div>;
  if (findings.data.items.length === 0) {
    return <EmptyState icon={<Bug className="size-5" />} title="No defects reproduced yet" description="Failures found by campaigns appear here with their minimal input and a regression test." />;
  }
  return (
    <Table>
      <thead><tr><Th>Severity</Th><Th>Defect</Th><Th>Function</Th><Th>Evidence</Th><Th>Status</Th><Th className="text-right">Last seen</Th></tr></thead>
      <tbody>
        {findings.data.items.map((f) => (
          <Tr key={f.id}>
            <Td><SeverityBadge severity={f.severity} /></Td>
            <Td className="max-w-sm">
              <Link href={`/findings/${f.id}`} className="block truncate font-medium text-fg hover:underline">{f.message}</Link>
              <p className="text-xs text-fg-3"><span className="font-mono">{f.ruleId}</span> · {f.ruleName}</p>
            </Td>
            <Td className="font-mono text-xs">{f.symbolKey?.replace("#", " › ") ?? `${f.path}:${f.startLine}`}</Td>
            <Td><EvidenceBadge level={f.evidenceLevel} source={f.source} /></Td>
            <Td><FindingStatusBadge status={f.status} /></Td>
            <Td className="text-right text-xs">{formatRelative(f.lastSeenAt)}</Td>
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}

function BreakMyCode() {
  const { can } = useSession();
  const router = useRouter();
  const params = useSearchParams();
  const tab: Tab = params.get("tab") === "mutation" ? "mutation" : "fuzzing";
  const { projectId, projects } = useSelectedProject();
  const campaigns = useFuzzCampaigns(projectId);
  const revisions = useRevisions(projectId ?? "");
  const active = useFindings(projectId, { source: "FUZZING", status: [...ACTIVE_FINDING_STATUSES], size: 1 });
  const [starting, setStarting] = useState(false);
  const latestSnapshot = revisions.data?.items[0];
  const last = campaigns.data?.items.find((c) => c.campaign != null)?.campaign;

  return (
    <>
      <PageHeader
        title="Break My Code"
        description="Attack your code and your tests: fuzzing finds inputs that break functions, mutation testing finds changes your tests would not notice."
        actions={
          <>
            <ProjectPicker projectId={projectId} projects={projects} />
            {tab === "fuzzing" && can("job.submit") && projectId && (
              <Button variant="primary" onClick={() => setStarting(true)} disabled={!latestSnapshot}>
                <Play className="size-4" /> New campaign
              </Button>
            )}
          </>
        }
      />
      {!projectId ? (
        projects.isPending ? <Spinner /> : <EmptyState icon={<Bug className="size-5" />} title="No projects" description="Create a project and import a snapshot first." />
      ) : (
        <div className="space-y-6">
          <div className="flex gap-1 border-b border-border" role="tablist">
            {([["fuzzing", "Fuzzing"], ["mutation", "Mutation testing"]] as const).map(([id, label]) => (
              <button key={id} role="tab" aria-selected={tab === id}
                onClick={() => { const next = new URLSearchParams(params); if (id === "fuzzing") next.delete("tab"); else next.set("tab", id); router.replace(`/break-my-code?${next}`); }}
                className={cn("-mb-px border-b-2 px-3 py-2.5 text-[13px]", tab === id ? "border-accent font-medium text-fg" : "border-transparent text-fg-3 hover:text-fg")}>
                {label}
              </button>
            ))}
          </div>
          {tab === "mutation" ? <MutationOverview projectId={projectId} snapshotId={latestSnapshot?.id} canSubmit={can("job.submit")} /> : (
            <>
              <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                <Stat label="Open reproduced defects" value={active.data?.total ?? "—"} detail="Fuzzing findings not yet fixed" />
                <Stat label="Functions tested" value={last?.targetsTested ?? "—"} detail={last ? `${last.targetsSkipped} skipped in the last campaign` : "No campaign yet"} />
                <Stat label="Calls executed" value={last ? last.totalCalls.toLocaleString() : "—"} detail={last ? `in ${formatDuration(last.durationMs)}` : undefined} />
                <Stat label="Last campaign" value={last ? formatRelative(last.createdAt) : "—"} detail={last ? `seed ${last.seed} · ${last.iterationsPerTarget} calls per function` : undefined} />
              </div>

              <Card>
                <CardHeader title="Reproduced defects" description="Every failure keeps its minimal input. A defect is marked fixed only when that input stops failing." />
                <Defects projectId={projectId} />
              </Card>

              <Card>
                <CardHeader title="Campaigns" />
                {campaigns.isPending ? <Spinner /> : campaigns.isError ? <div className="p-4"><ErrorState error={campaigns.error} /></div> : campaigns.data.items.length === 0 ? (
                  <EmptyState title="No campaigns yet" description="Start a campaign to fuzz the latest snapshot." />
                ) : (
                  <Table>
                    <thead><tr><Th>Campaign</Th><Th>Status</Th><Th>Functions</Th><Th>Calls</Th><Th>Failures</Th><Th className="text-right">Duration</Th><Th className="text-right">Queued</Th></tr></thead>
                    <tbody>
                      {campaigns.data.items.map(({ job, campaign }) => (
                        <Tr key={job.id}>
                          <Td><Link href={`/break-my-code/${job.id}`} className="font-mono text-xs text-fg hover:underline">{job.id.slice(-8)}</Link></Td>
                          <Td><JobStateBadge state={job.state} jobType={job.jobType} /></Td>
                          <Td className="text-xs">{campaign ? <>{campaign.targetsTested} tested · {campaign.targetsSkipped} skipped</> : "—"}</Td>
                          <Td className="tabular text-xs">{campaign ? campaign.totalCalls.toLocaleString() : "—"}</Td>
                          <Td className="tabular text-xs">{campaign ? <span className={campaign.failures > 0 ? "font-medium text-danger" : undefined}>{campaign.failures}</span> : "—"}</Td>
                          <Td className="tabular text-right text-xs">{formatDuration(job.resultSummary?.durationMs as number | undefined)}</Td>
                          <Td className="text-right text-xs">{formatRelative(job.queuedAt)}</Td>
                        </Tr>
                      ))}
                    </tbody>
                  </Table>
                )}
              </Card>
              <FuzzingNote />
            </>
          )}
        </div>
      )}
      {projectId && <StartCampaign projectId={projectId} snapshotId={latestSnapshot?.id} open={starting} onClose={() => setStarting(false)} />}
    </>
  );
}

export default function BreakMyCodePage() {
  return (
    <Suspense fallback={<Spinner />}>
      <BreakMyCode />
    </Suspense>
  );
}
