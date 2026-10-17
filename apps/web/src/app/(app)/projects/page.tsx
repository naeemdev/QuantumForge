"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { FolderGit2, Plus, Search } from "lucide-react";
import { api, call } from "@/lib/api";
import { useProjects } from "@/lib/queries";
import { useOrgId, useSession } from "@/lib/session";
import { formatRelative } from "@/lib/format";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Textarea } from "@/components/ui/input";
import { PageHeader } from "@/components/ui/page-header";
import { EmptyState, ErrorState, InlineError, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";

function CreateProjectDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const orgId = useOrgId();
  const router = useRouter();
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const project = await call(api.POST("/api/v1/projects", { body: { organizationId: orgId, name, description: description || undefined } }));
      await qc.invalidateQueries({ queryKey: ["projects"] });
      router.push(`/projects/${project.id}`);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} title="New project" description="A project groups repositories, snapshots and their test history.">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Name" htmlFor="p-name">
          <Input id="p-name" required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} placeholder="Payment Gateway API" autoFocus />
        </Field>
        <Field label="Description" htmlFor="p-desc" hint="Optional">
          <Textarea id="p-desc" maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        <InlineError error={error} />
        <div className="flex justify-end gap-2">
          <Button type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" loading={busy}>Create project</Button>
        </div>
      </form>
    </Dialog>
  );
}

export default function ProjectsPage() {
  const { can } = useSession();
  const [q, setQ] = useState("");
  const [creating, setCreating] = useState(false);
  const projects = useProjects(q);

  return (
    <>
      <PageHeader
        title="Projects"
        description="Each project holds one or more repositories and their immutable source snapshots."
        actions={can("project.create") && (
          <Button variant="primary" onClick={() => setCreating(true)}>
            <Plus className="size-4" /> New project
          </Button>
        )}
      />
      <Card>
        <div className="border-b border-border p-3">
          <div className="relative max-w-xs">
            <Search className="pointer-events-none absolute left-2.5 top-2 size-4 text-fg-3" aria-hidden />
            <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter projects" className="pl-8" aria-label="Filter projects" />
          </div>
        </div>
        {projects.isPending ? (
          <Spinner />
        ) : projects.isError ? (
          <div className="p-4"><ErrorState error={projects.error} onRetry={() => projects.refetch()} /></div>
        ) : projects.data.items.length === 0 ? (
          <EmptyState
            icon={<FolderGit2 className="size-5" />}
            title={q ? "No matching projects" : "No projects yet"}
            description={q ? undefined : "Create a project to import source code and run tests in isolated workers."}
            action={!q && can("project.create") && <Button variant="primary" onClick={() => setCreating(true)}>New project</Button>}
          />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Name</Th>
                <Th>Slug</Th>
                <Th>Status</Th>
                <Th className="text-right">Updated</Th>
              </tr>
            </thead>
            <tbody>
              {projects.data.items.map((p) => (
                <Tr key={p.id}>
                  <Td>
                    <Link href={`/projects/${p.id}`} className="font-medium text-fg hover:underline">{p.name}</Link>
                    {p.description && <p className="max-w-md truncate text-xs text-fg-3">{p.description}</p>}
                  </Td>
                  <Td className="font-mono text-xs">{p.slug}</Td>
                  <Td><Badge tone={p.status === "ACTIVE" ? "success" : "neutral"}>{p.status === "ACTIVE" ? "Active" : "Archived"}</Badge></Td>
                  <Td className="text-right text-xs">{formatRelative(p.updatedAt)}</Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      <CreateProjectDialog open={creating} onClose={() => setCreating(false)} />
    </>
  );
}
