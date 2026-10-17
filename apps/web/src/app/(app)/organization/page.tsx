"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Check, KeyRound, Minus, UserPlus } from "lucide-react";
import { api, call } from "@/lib/api";
import { useApiTokens, useAuditEvents, useInvitations, useMembers, useRoles } from "@/lib/queries";
import { useOrgId, useSession } from "@/lib/session";
import { formatDateTime, formatRelative } from "@/lib/format";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { CopyButton } from "@/components/ui/copy";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";
import { PageHeader } from "@/components/ui/page-header";
import { EmptyState, ErrorState, InlineError, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";
import { cn } from "@/components/ui/cn";

type Section = "members" | "roles" | "tokens" | "audit";

const roleLabel: Record<string, string> = { org_owner: "Owner", org_admin: "Admin", org_member: "Member", org_viewer: "Viewer" };

function SecretOnce({ secret, title, note }: { secret: string; title: string; note: string }) {
  return (
    <div className="space-y-2 rounded-md border border-warning/30 bg-warning-soft p-3">
      <p className="text-[13px] font-medium text-fg">{title}</p>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 truncate rounded bg-surface px-2 py-1 font-mono text-xs">{secret}</code>
        <CopyButton value={secret} />
      </div>
      <p className="text-xs text-fg-2">{note}</p>
    </div>
  );
}

function InviteDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const orgId = useOrgId();
  const { organization } = useSession();
  const qc = useQueryClient();
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("org_member");
  const [token, setToken] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const close = () => {
    setToken(null);
    setEmail("");
    setError(null);
    onClose();
  };

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await call(api.POST("/api/v1/organizations/{id}/invitations", { params: { path: { id: orgId } }, body: { email, role } }));
      setToken(r.token);
      await qc.invalidateQueries({ queryKey: ["invitations", orgId] });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onClose={close} title="Invite member" description="The invitation only works for the account signed in with this email address.">
      {token ? (
        <>
          <SecretOnce secret={token} title={`Invitation for ${email}`} note="Share this token with the invitee. It is shown once, expires in 7 days and can be revoked." />
          <div className="flex justify-end"><Button onClick={close}>Done</Button></div>
        </>
      ) : (
        <form onSubmit={submit} className="space-y-4">
          <Field label="Email" htmlFor="i-email">
            <Input id="i-email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoFocus />
          </Field>
          <Field label="Role" htmlFor="i-role">
            <Select id="i-role" value={role} onChange={(e) => setRole(e.target.value)}>
              <option value="org_viewer">Viewer</option>
              <option value="org_member">Member</option>
              <option value="org_admin">Admin</option>
              {organization?.role === "org_owner" && <option value="org_owner">Owner</option>}
            </Select>
          </Field>
          <InlineError error={error} />
          <div className="flex justify-end gap-2">
            <Button type="button" onClick={close}>Cancel</Button>
            <Button type="submit" variant="primary" loading={busy}>Create invitation</Button>
          </div>
        </form>
      )}
    </Dialog>
  );
}

function MembersSection() {
  const orgId = useOrgId();
  const { can, me } = useSession();
  const qc = useQueryClient();
  const manage = can("org.members.manage");
  const members = useMembers();
  const invitations = useInvitations(manage);
  const [inviting, setInviting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function changeRole(membershipId: string, role: string, version: number) {
    setError(null);
    try {
      await call(api.PATCH("/api/v1/organizations/{id}/members/{membershipId}", { params: { path: { id: orgId, membershipId } }, body: { role, version } }));
    } catch (err) {
      setError(err);
    }
    await qc.invalidateQueries({ queryKey: ["members", orgId] });
  }

  async function remove(membershipId: string) {
    setError(null);
    try {
      await call(api.DELETE("/api/v1/organizations/{id}/members/{membershipId}", { params: { path: { id: orgId, membershipId } } }));
    } catch (err) {
      setError(err);
    }
    await qc.invalidateQueries({ queryKey: ["members", orgId] });
  }

  async function revokeInvitation(invitationId: string) {
    await call(api.DELETE("/api/v1/organizations/{id}/invitations/{invitationId}", { params: { path: { id: orgId, invitationId } } }));
    await qc.invalidateQueries({ queryKey: ["invitations", orgId] });
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader title="Members" actions={manage && <Button size="sm" variant="primary" onClick={() => setInviting(true)}><UserPlus className="size-3.5" /> Invite</Button>} />
        {error ? <div className="border-b border-border p-3"><ErrorState error={error} /></div> : null}
        {members.isPending ? <Spinner /> : members.isError ? <div className="p-4"><ErrorState error={members.error} /></div> : (
          <Table>
            <thead><tr><Th>Member</Th><Th>Role</Th><Th>Joined</Th><Th /></tr></thead>
            <tbody>
              {members.data.items.map((m) => (
                <Tr key={m.membershipId}>
                  <Td>
                    <p className="font-medium text-fg">{m.displayName ?? m.email}{m.userId === me?.id && <span className="ml-2 text-xs text-fg-3">(you)</span>}</p>
                    <p className="text-xs text-fg-3">{m.email}</p>
                  </Td>
                  <Td>
                    {manage ? (
                      <Select value={m.roleId} onChange={(e) => void changeRole(m.membershipId, e.target.value, m.version)} className="w-32" aria-label={`Role for ${m.email}`}>
                        {Object.entries(roleLabel).map(([id, label]) => <option key={id} value={id}>{label}</option>)}
                      </Select>
                    ) : <Badge>{roleLabel[m.roleId] ?? m.roleId}</Badge>}
                  </Td>
                  <Td className="text-xs">{formatRelative(m.joinedAt)}</Td>
                  <Td className="text-right">{manage && m.userId !== me?.id && <Button size="sm" variant="ghost" onClick={() => void remove(m.membershipId)}>Remove</Button>}</Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      {manage && (
        <Card>
          <CardHeader title="Invitations" />
          {invitations.isPending ? <Spinner /> : invitations.isError ? <div className="p-4"><ErrorState error={invitations.error} /></div> : invitations.data.length === 0 ? (
            <EmptyState title="No invitations" />
          ) : (
            <Table>
              <thead><tr><Th>Email</Th><Th>Role</Th><Th>Status</Th><Th>Expires</Th><Th /></tr></thead>
              <tbody>
                {invitations.data.map((i) => (
                  <Tr key={i.id}>
                    <Td className="text-fg">{i.email}</Td>
                    <Td>{roleLabel[i.roleId] ?? i.roleId}</Td>
                    <Td><Badge tone={i.status === "PENDING" ? "info" : i.status === "ACCEPTED" ? "success" : "neutral"}>{i.status.toLowerCase()}</Badge></Td>
                    <Td className="text-xs">{formatDateTime(i.expiresAt)}</Td>
                    <Td className="text-right">{i.status === "PENDING" && <Button size="sm" variant="ghost" onClick={() => void revokeInvitation(i.id)}>Revoke</Button>}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>
      )}
      <InviteDialog open={inviting} onClose={() => setInviting(false)} />
    </div>
  );
}

function RolesSection() {
  const roles = useRoles();
  if (roles.isPending) return <Spinner />;
  if (roles.isError) return <ErrorState error={roles.error} />;
  return (
    <Card>
      <CardHeader title="Roles and permissions" description="Built-in roles. Permissions are always checked by the API in the context of this organization." />
      <Table>
        <thead>
          <tr>
            <Th>Permission</Th>
            {roles.data.roles.map((r) => <Th key={r.id} className="text-center">{r.name}</Th>)}
          </tr>
        </thead>
        <tbody>
          {roles.data.permissions.map((p) => (
            <Tr key={p.code}>
              <Td>
                <p className="font-mono text-xs text-fg">{p.code}</p>
                <p className="text-xs text-fg-3">{p.description}</p>
              </Td>
              {roles.data.roles.map((r) => (
                <Td key={r.id} className="text-center">
                  {r.permissions.includes(p.code) ? <Check className="mx-auto size-4 text-success" aria-label="granted" /> : <Minus className="mx-auto size-4 text-fg-3" aria-label="not granted" />}
                </Td>
              ))}
            </Tr>
          ))}
        </tbody>
      </Table>
    </Card>
  );
}

function TokensSection() {
  const orgId = useOrgId();
  const qc = useQueryClient();
  const tokens = useApiTokens();
  const [now] = useState(() => Date.now());
  const [name, setName] = useState("");
  const [days, setDays] = useState("30");
  const [secret, setSecret] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await call(api.POST("/api/v1/organizations/{id}/api-tokens", { params: { path: { id: orgId } }, body: { name, expiresInDays: Number(days) } }));
      setSecret(r.secret);
      setName("");
      await qc.invalidateQueries({ queryKey: ["api-tokens", orgId] });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  async function revoke(tokenId: string) {
    await call(api.DELETE("/api/v1/organizations/{id}/api-tokens/{tokenId}", { params: { path: { id: orgId, tokenId } } }));
    await qc.invalidateQueries({ queryKey: ["api-tokens", orgId] });
  }

  return (
    <Card>
      <CardHeader title="Personal API tokens" description="For the CLI and CI. A token acts as you, only in this organization, with your current permissions." />
      <form onSubmit={create} className="flex flex-wrap items-end gap-3 border-b border-border p-4">
        <div className="w-64"><Field label="Name" htmlFor="t-name"><Input id="t-name" required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} placeholder="ci-pipeline" /></Field></div>
        <div className="w-36">
          <Field label="Expires in" htmlFor="t-days">
            <Select id="t-days" value={days} onChange={(e) => setDays(e.target.value)}>
              {["7", "30", "90", "365"].map((d) => <option key={d} value={d}>{d} days</option>)}
            </Select>
          </Field>
        </div>
        <Button type="submit" variant="primary" loading={busy}><KeyRound className="size-3.5" /> Create token</Button>
        <InlineError error={error} />
      </form>
      {secret && <div className="border-b border-border p-4"><SecretOnce secret={secret} title="New token" note="Copy it now. Only a hash is stored; it cannot be shown again." /></div>}
      {tokens.isPending ? <Spinner /> : tokens.isError ? <div className="p-4"><ErrorState error={tokens.error} /></div> : tokens.data.length === 0 ? (
        <EmptyState title="No tokens" />
      ) : (
        <Table>
          <thead><tr><Th>Name</Th><Th>Prefix</Th><Th>Last used</Th><Th>Expires</Th><Th>Status</Th><Th /></tr></thead>
          <tbody>
            {tokens.data.map((t) => {
              const expired = Date.parse(t.expiresAt) < now;
              return (
                <Tr key={t.id}>
                  <Td className="text-fg">{t.name}</Td>
                  <Td className="font-mono text-xs">{t.tokenPrefix}…</Td>
                  <Td className="text-xs">{formatRelative(t.lastUsedAt)}</Td>
                  <Td className="text-xs">{formatDateTime(t.expiresAt)}</Td>
                  <Td>{t.revokedAt ? <Badge>revoked</Badge> : expired ? <Badge tone="warning">expired</Badge> : <Badge tone="success">active</Badge>}</Td>
                  <Td className="text-right">{!t.revokedAt && !expired && <Button size="sm" variant="ghost" onClick={() => void revoke(t.id)}>Revoke</Button>}</Td>
                </Tr>
              );
            })}
          </tbody>
        </Table>
      )}
    </Card>
  );
}

function AuditSection() {
  const [page, setPage] = useState(0);
  const audit = useAuditEvents(page, true);
  if (audit.isPending) return <Spinner />;
  if (audit.isError) return <ErrorState error={audit.error} />;
  return (
    <Card>
      <CardHeader title="Audit trail" description="Append-only. Entries cannot be edited or deleted, including by administrators." />
      <Table>
        <thead><tr><Th>Time</Th><Th>Action</Th><Th>Actor</Th><Th>Target</Th><Th>Outcome</Th><Th>Request</Th></tr></thead>
        <tbody>
          {audit.data.items.map((e) => (
            <Tr key={e.eventId}>
              <Td className="tabular whitespace-nowrap text-xs">{formatDateTime(e.occurredAt)}</Td>
              <Td className="font-mono text-xs text-fg">{e.action}</Td>
              <Td className="text-xs">{e.actorType.toLowerCase().replace("_", " ")} <span className="font-mono text-fg-3">{e.actorId?.slice(-8)}</span></Td>
              <Td className="text-xs">{e.targetType ?? "—"} <span className="font-mono text-fg-3">{e.targetId?.slice(-8)}</span></Td>
              <Td><Badge tone={e.outcome === "SUCCESS" ? "success" : "danger"}>{e.outcome.toLowerCase()}</Badge></Td>
              <Td className="font-mono text-[11px] text-fg-3">{e.requestId?.slice(0, 8) ?? "—"}</Td>
            </Tr>
          ))}
        </tbody>
      </Table>
      <div className="flex items-center justify-between border-t border-border px-4 py-2 text-xs text-fg-3">
        <span>{audit.data.total} events</span>
        <div className="flex gap-2">
          <Button size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
          <Button size="sm" disabled={(page + 1) * audit.data.size >= audit.data.total} onClick={() => setPage((p) => p + 1)}>Next</Button>
        </div>
      </div>
    </Card>
  );
}

export default function OrganizationPage() {
  const { organization, can } = useSession();
  const sections: { id: Section; label: string; visible: boolean }[] = [
    { id: "members", label: "Members", visible: true },
    { id: "roles", label: "Roles", visible: true },
    { id: "tokens", label: "API tokens", visible: can("org.tokens.manage") },
    { id: "audit", label: "Audit trail", visible: can("org.audit.read") },
  ];
  const [section, setSection] = useState<Section>("members");

  return (
    <>
      <PageHeader title={organization?.organizationName ?? "Organization"} description={<>Slug <span className="font-mono">{organization?.organizationSlug}</span> · your role: {roleLabel[organization?.role ?? ""] ?? organization?.role}</>} />
      <div className="mb-4 flex gap-1 border-b border-border" role="tablist">
        {sections.filter((s) => s.visible).map((s) => (
          <button key={s.id} role="tab" aria-selected={section === s.id} onClick={() => setSection(s.id)}
            className={cn("-mb-px border-b-2 px-3 py-2 text-[13px]", section === s.id ? "border-accent font-medium text-fg" : "border-transparent text-fg-3 hover:text-fg")}>
            {s.label}
          </button>
        ))}
      </div>
      {section === "members" && <MembersSection />}
      {section === "roles" && <RolesSection />}
      {section === "tokens" && <TokensSection />}
      {section === "audit" && <AuditSection />}
    </>
  );
}
