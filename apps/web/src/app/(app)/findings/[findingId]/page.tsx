"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { FindingStatus } from "@quantumforge/contracts";
import { api, call } from "@/lib/api";
import { useChangeFindingStatus, useFinding, useProject } from "@/lib/queries";
import { useSession } from "@/lib/session";
import { formatDateTime, formatRelative } from "@/lib/format";
import { CodeViewer } from "@/components/code-viewer";
import { EvidenceNote, FindingStatusBadge, SeverityBadge, findingStatusLabel } from "@/components/findings";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { Dialog } from "@/components/ui/dialog";
import { Field, Textarea } from "@/components/ui/input";
import { PageHeader } from "@/components/ui/page-header";
import { ErrorState, InlineError, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";

type Action = { status: "OPEN" | "CONFIRMED" | "FALSE_POSITIVE" | "ACCEPTED_RISK"; label: string; needsReason: boolean; variant: "primary" | "secondary" };

function actionsFor(status: FindingStatus): Action[] {
  if (status === "FIXED") return [];
  const all: Action[] = [
    { status: "CONFIRMED", label: "Confirm", needsReason: false, variant: "primary" },
    { status: "FALSE_POSITIVE", label: "Mark false positive", needsReason: true, variant: "secondary" },
    { status: "ACCEPTED_RISK", label: "Accept risk", needsReason: true, variant: "secondary" },
    { status: "OPEN", label: "Reopen", needsReason: false, variant: "secondary" },
  ];
  return all.filter((a) => a.status !== status && !(a.status === "OPEN" && (status === "OPEN" || status === "REOPENED")));
}

export default function FindingPage() {
  const { findingId } = useParams<{ findingId: string }>();
  const { can } = useSession();
  const detail = useFinding(findingId);
  const project = useProject(detail.data?.finding.projectId ?? "");
  const change = useChangeFindingStatus(findingId);
  const [pending, setPending] = useState<Action | null>(null);
  const [reason, setReason] = useState("");
  const f = detail.data?.finding;
  const source = useQuery({
    queryKey: ["content", f?.lastSeenSnapshotId, f?.path],
    queryFn: () => call(api.GET("/api/v1/snapshots/{id}/content", { params: { path: { id: f!.lastSeenSnapshotId }, query: { path: f!.path } } })),
    enabled: f != null,
    staleTime: Infinity,
    retry: false,
  });

  if (detail.isPending) return <Spinner />;
  if (detail.isError) return <ErrorState error={detail.error} />;
  const { finding, rule, occurrences, history } = detail.data;
  const latest = occurrences[0];

  async function apply(action: Action, why?: string) {
    await change.mutateAsync({ status: action.status, reason: why || undefined, version: finding.version });
    setPending(null);
    setReason("");
  }

  return (
    <>
      <PageHeader
        breadcrumbs={[{ label: "Findings", href: `/findings?project=${finding.projectId}` }, ...(project.data ? [{ label: project.data.name, href: `/projects/${finding.projectId}` }] : []), { label: finding.ruleId }]}
        title={finding.message}
        description={<span className="flex flex-wrap items-center gap-2"><SeverityBadge severity={finding.severity} /><FindingStatusBadge status={finding.status} /><Badge>Suspected · static analysis</Badge><span className="text-xs text-fg-3">{finding.confidence} confidence</span></span>}
        actions={can("finding.triage") && actionsFor(finding.status).map((a) => (
          <Button key={a.status} variant={a.variant} onClick={() => (a.needsReason ? setPending(a) : void apply(a))} loading={change.isPending && !pending}>{a.label}</Button>
        ))}
      />
      <InlineError error={change.error} />

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card className="overflow-hidden">
            <CardHeader
              title={<span className="font-mono">{finding.path}:{finding.startLine}</span>}
              description={finding.symbolKey ? `In ${finding.symbolKey.split("#")[1]}` : undefined}
              actions={<Link className="text-xs text-fg-2 hover:text-fg" href={`/projects/${finding.projectId}/source?snapshot=${finding.lastSeenSnapshotId}&path=${encodeURIComponent(finding.path)}&line=${finding.startLine}`}>Open in source</Link>}
            />
            {source.isPending ? <Spinner /> : source.isError ? <pre className="overflow-auto bg-surface-2 p-4 font-mono text-xs">{finding.snippet}</pre> : (
              <CodeViewer
                value={source.data.content}
                language={source.data.language}
                path={`finding/${finding.id}/${finding.path}`}
                height="22rem"
                revealLine={finding.startLine}
                markers={latest ? [{ startLine: latest.startLine, startColumn: latest.startColumn, endLine: latest.endLine, endColumn: latest.endColumn, message: `${rule.id}: ${latest.message}`, severity: latest.severity }] : []}
              />
            )}
          </Card>

          <Card>
            <CardHeader title={<>{rule.name} <span className="ml-1 font-mono text-xs font-normal text-fg-3">{rule.id} v{rule.version}</span></>} description={rule.summary} />
            <CardBody className="space-y-3 text-[13px]">
              <p className="text-fg-2">{rule.description}</p>
              <div>
                <p className="text-xs font-medium text-fg">Remediation</p>
                <p className="mt-1 text-fg-2">{rule.remediation}</p>
              </div>
              <p className="text-xs text-fg-3">Category: {rule.category} · Languages: {rule.languages.join(", ")}</p>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="Occurrences" description="Each analysis that detected this finding. Original evidence is never modified." />
            <Table>
              <thead><tr><Th>Analysis</Th><Th>Location</Th><Th>Rule version</Th><Th className="text-right">Detected</Th></tr></thead>
              <tbody>
                {occurrences.map((o) => (
                  <Tr key={o.jobId}>
                    <Td><Link href={`/test-runs/${o.jobId}`} className="font-mono text-xs text-fg hover:underline">{o.jobId.slice(-8)}</Link></Td>
                    <Td className="font-mono text-xs">{o.path}:{o.startLine}</Td>
                    <Td className="text-xs">v{o.ruleVersion}</Td>
                    <Td className="text-right text-xs">{formatRelative(o.createdAt)}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          </Card>
        </div>

        <div className="space-y-6">
          <Card>
            <CardHeader title="Status history" />
            <ol className="divide-y divide-border">
              {history.map((h, i) => (
                <li key={i} className="px-4 py-2.5">
                  <p className="text-[13px] text-fg">
                    {h.fromStatus ? <>{findingStatusLabel(h.fromStatus)} → </> : null}<span className="font-medium">{findingStatusLabel(h.toStatus)}</span>
                  </p>
                  {h.reason && <p className="mt-0.5 text-xs text-fg-2">“{h.reason}”</p>}
                  <p className="mt-0.5 text-xs text-fg-3">{h.actorType === "SYSTEM" ? "Analysis" : h.actorType === "API_TOKEN" ? "API token" : "User"} · {formatDateTime(h.changedAt)}</p>
                </li>
              ))}
            </ol>
          </Card>
          <Card>
            <CardHeader title="Tracking" />
            <dl className="divide-y divide-border text-[13px]">
              {[["First seen", formatDateTime(finding.firstSeenAt)], ["Last seen", formatDateTime(finding.lastSeenAt)], ["Resolved", formatDateTime(finding.resolvedAt)], ["Occurrences", String(occurrences.length)]].map(([k, v]) => (
                <div key={k} className="flex justify-between px-4 py-2"><dt className="text-fg-3">{k}</dt><dd className="text-fg-2">{v}</dd></div>
              ))}
            </dl>
          </Card>
          <EvidenceNote />
        </div>
      </div>

      <Dialog open={pending != null} onClose={() => setPending(null)} title={pending?.label ?? ""} description="The reason is stored in the finding's history and audit trail.">
        <form onSubmit={(e) => { e.preventDefault(); if (pending) void apply(pending, reason); }} className="space-y-4">
          <Field label="Reason" htmlFor="reason">
            <Textarea id="reason" required maxLength={2000} value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
          </Field>
          <InlineError error={change.error} />
          <div className="flex justify-end gap-2">
            <Button type="button" onClick={() => setPending(null)}>Cancel</Button>
            <Button type="submit" variant="primary" loading={change.isPending}>{pending?.label}</Button>
          </div>
        </form>
      </Dialog>
    </>
  );
}
