"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { api, call } from "@/lib/api";
import { useSession } from "@/lib/session";
import { Logo } from "@/components/app-shell";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/input";
import { InlineError, Spinner } from "@/components/ui/states";

export default function OnboardingPage() {
  const router = useRouter();
  const qc = useQueryClient();
  const { token, me, setOrganizationId, signOut } = useSession();
  const [name, setName] = useState("");
  const [invite, setInvite] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState<"create" | "join" | null>(null);

  useEffect(() => {
    if (!token) router.replace("/login");
  }, [token, router]);

  async function finish(orgId: string) {
    await qc.invalidateQueries({ queryKey: ["me"] });
    setOrganizationId(orgId);
    router.replace("/overview");
  }

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setBusy("create");
    setError(null);
    try {
      const org = await call(api.POST("/api/v1/organizations", { body: { name } }));
      await finish(org.id);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(null);
    }
  }

  async function join(e: React.FormEvent) {
    e.preventDefault();
    setBusy("join");
    setError(null);
    try {
      const org = await call(api.POST("/api/v1/invitations/accept", { body: { token: invite.trim() } }));
      await finish(org.id);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(null);
    }
  }

  if (!me) return <Spinner />;

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-md">
        <div className="mb-8 flex items-center gap-2.5">
          <Logo className="size-7" />
          <p className="text-sm font-semibold tracking-tight">QuantumForge</p>
        </div>
        <div className="rounded-lg border border-border bg-surface">
          <form onSubmit={create} className="space-y-4 p-6">
            <div>
              <h1 className="text-base font-semibold">Create an organization</h1>
              <p className="mt-1 text-[13px] text-fg-2">Organizations hold projects, members and execution history. You can create more later.</p>
            </div>
            <Field label="Organization name" htmlFor="org">
              <Input id="org" required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} placeholder="Demo Engineering" />
            </Field>
            <Button type="submit" variant="primary" loading={busy === "create"}>
              Create organization
            </Button>
          </form>
          <form onSubmit={join} className="space-y-4 border-t border-border p-6">
            <div>
              <h2 className="text-[13px] font-semibold">Or join with an invitation</h2>
              <p className="mt-1 text-[13px] text-fg-2">Paste the invitation token you received. It must have been issued to {me.email}.</p>
            </div>
            <Input required value={invite} onChange={(e) => setInvite(e.target.value)} placeholder="qf_inv_…" className="font-mono" aria-label="Invitation token" />
            <Button type="submit" loading={busy === "join"}>
              Accept invitation
            </Button>
          </form>
          <div className="border-t border-border px-6 py-3">
            <InlineError error={error} />
            <button onClick={signOut} className="text-xs text-fg-3 hover:text-fg">
              Sign out
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
