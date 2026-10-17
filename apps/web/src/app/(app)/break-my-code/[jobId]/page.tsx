"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { X } from "lucide-react";
import { TERMINAL_JOB_STATES, type FuzzCampaignDetail } from "@quantumforge/contracts";
import { useCancelJob, useFuzzCampaign, useProject } from "@/lib/queries";
import { useSession } from "@/lib/session";
import { formatDuration, formatRelative } from "@/lib/format";
import { FuzzingNote, ReproductionCard } from "@/components/fuzzing";
import { JobStateBadge } from "@/components/job-state";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { PageHeader } from "@/components/ui/page-header";
import { Stat } from "@/components/ui/stat";
import { EmptyState, ErrorState, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";
import { cn } from "@/components/ui/cn";

function Verdict({ detail }: { detail: FuzzCampaignDetail }) {
  const { job, campaign } = detail;
  const summary = job.resultSummary?.fuzz as { newFindings?: number; reopened?: number; fixed?: number } | undefined;
  let text: string;
  let tone = "border-border bg-surface-2";
  if (job.state === "SUCCEEDED" && campaign) {
    if (campaign.failures > 0) {
      text = `${campaign.failures} ${campaign.failures === 1 ? "failure" : "failures"} found in ${campaign.targetsTested} functions (${summary?.newFindings ?? 0} new, ${summary?.reopened ?? 0} reopened). Each was shrunk to a minimal input and re-run in fresh processes; the re-run count is shown with it.`;
      tone = "border-danger/30 bg-danger-soft";
    } else {
      text = `No failures in ${campaign.totalCalls.toLocaleString()} calls to ${campaign.targetsTested} functions. This means the campaign's checks found nothing in the inputs it tried, not that the code is correct.`;
    }
    if ((summary?.fixed ?? 0) > 0) text += ` ${summary?.fixed} previously reproduced ${summary?.fixed === 1 ? "defect no longer fails" : "defects no longer fail"} with ${summary?.fixed === 1 ? "its" : "their"} stored input and ${summary?.fixed === 1 ? "was" : "were"} marked fixed.`;
  } else if (TERMINAL_JOB_STATES.includes(job.state)) {
    text = job.state === "INFRA_ERROR"
      ? `The platform could not complete this campaign (${job.failureReason ?? "infrastructure error"}). This says nothing about the code.`
      : `The campaign did not complete: ${job.failureReason ?? job.state.toLowerCase().replace("_", " ")}.`;
  } else {
    text = "The campaign has not finished yet.";
  }
  return <div className={cn("rounded-md border px-4 py-3 text-[13px] text-fg", tone)}>{text}</div>;
}

export default function CampaignPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const { can } = useSession();
  const detail = useFuzzCampaign(jobId);
  const project = useProject(detail.data?.job.projectId ?? "");
  const cancel = useCancelJob(jobId);
  const [showSkipped, setShowSkipped] = useState(false);

  if (detail.isPending) return <Spinner />;
  if (detail.isError) return <ErrorState error={detail.error} />;
  const { job, campaign, targets, reproductions } = detail.data;
  const terminal = TERMINAL_JOB_STATES.includes(job.state);
  const tested = targets.filter((t) => t.status === "TESTED");
  const skipped = targets.filter((t) => t.status === "SKIPPED");

  return (
    <>
      <PageHeader
        breadcrumbs={[
          { label: "Break My Code", href: `/break-my-code?project=${job.projectId}` },
          ...(project.data ? [{ label: project.data.name, href: `/projects/${job.projectId}` }] : []),
          { label: job.id.slice(-8) },
        ]}
        title={<span className="flex items-center gap-3">Fuzz campaign <span className="font-mono text-base text-fg-2">{job.id.slice(-8)}</span> <JobStateBadge state={job.state} jobType={job.jobType} /></span>}
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
        {campaign && (
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Stat label="Functions tested" value={campaign.targetsTested} detail={`${campaign.targetsSkipped} skipped`} />
            <Stat label="Calls" value={campaign.totalCalls.toLocaleString()} detail={`${campaign.iterationsPerTarget} per function · ${campaign.callTimeoutMs} ms limit each`} />
            <Stat label="Failures" value={campaign.failures} detail={campaign.corpusEntries > 0 ? `${campaign.corpusEntries} known inputs replayed first` : "No known inputs to replay"} />
            <Stat label="Duration" value={formatDuration(campaign.durationMs)} detail={`seed ${campaign.seed} · ${campaign.engineId} ${campaign.engineVersion}`} />
          </div>
        )}

        <section className="space-y-3">
          <h2 className="text-sm font-semibold text-fg">Failures</h2>
          {!terminal ? <Card><Spinner label="Campaign running" /></Card> : reproductions.length === 0 ? (
            <Card><EmptyState title="No failures" description="None of the oracles fired for the inputs this campaign generated." /></Card>
          ) : reproductions.map((r) => <ReproductionCard key={r.id} reproduction={r} showFinding />)}
        </section>

        {campaign && (
          <Card>
            <CardHeader
              title="Functions"
              description="Exported functions discovered in the snapshot. Skipped ones could not be called with inputs generated from their types."
              actions={skipped.length > 0 && (
                <Button size="sm" variant="ghost" onClick={() => setShowSkipped((s) => !s)}>{showSkipped ? "Hide" : "Show"} {skipped.length} skipped</Button>
              )}
            />
            <Table>
              <thead><tr><Th>Function</Th><Th>Calls</Th><Th>Rejected inputs</Th><Th>Failures</Th><Th className="text-right">Time</Th></tr></thead>
              <tbody>
                {tested.map((t) => (
                  <Tr key={`${t.path}#${t.exportName}`}>
                    <Td className="max-w-md">
                      <p className="truncate font-mono text-xs text-fg" title={t.signature ?? undefined}>{t.signature ?? t.exportName}</p>
                      <p className="text-xs text-fg-3">{t.path}:{t.line}</p>
                    </Td>
                    <Td className="tabular text-xs">{t.iterations}{t.replayed > 0 && <span className="text-fg-3"> ({t.replayed} replayed)</span>}</Td>
                    <Td className="tabular text-xs" title="Calls where the function threw its own validation error">{t.rejectedInputs}</Td>
                    <Td>{t.failures > 0 ? <Badge tone="danger">{t.failures}</Badge> : <span className="text-xs text-fg-3">0</span>}</Td>
                    <Td className="tabular text-right text-xs">{formatDuration(t.durationMs)}</Td>
                  </Tr>
                ))}
                {showSkipped && skipped.map((t) => (
                  <Tr key={`${t.path}#${t.exportName}`}>
                    <Td className="max-w-md">
                      <p className="truncate font-mono text-xs text-fg-2">{t.exportName}</p>
                      <p className="text-xs text-fg-3">{t.path}:{t.line}</p>
                    </Td>
                    <Td colSpan={4} className="text-xs text-fg-3">Skipped: {t.skipReason}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          </Card>
        )}

        {campaign && (
          <Card>
            <CardHeader title="What this campaign checks" description={`Recorded ${formatRelative(campaign.createdAt)}`} />
            <CardBody className="grid gap-6 text-[13px] md:grid-cols-2">
              <div>
                <p className="text-xs font-medium text-fg">Oracles</p>
                <ul className="mt-2 space-y-2 text-fg-2">
                  {campaign.oracles.map((o) => <li key={String(o.id)}><span className="font-mono text-xs text-fg">{String(o.id)}</span> {String(o.description)}</li>)}
                </ul>
              </div>
              <div>
                <p className="text-xs font-medium text-fg">Limitations</p>
                <ul className="mt-2 list-disc space-y-1 pl-4 text-fg-2">{campaign.limitations.map((l) => <li key={l}>{l}</li>)}</ul>
              </div>
            </CardBody>
          </Card>
        )}
        <FuzzingNote />
      </div>
    </>
  );
}
