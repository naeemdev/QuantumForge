"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api, call } from "@/lib/api";
import { oidcManager } from "@/lib/oidc";
import { tokenStore } from "@/lib/token-store";
import { useSession } from "@/lib/session";
import { Logo } from "@/components/app-shell";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/input";
import { ErrorState, InlineError, Spinner } from "@/components/ui/states";

function LoginForm() {
  const router = useRouter();
  const next = useSearchParams().get("next") ?? "/overview";
  const { token } = useSession();
  const config = useQuery({ queryKey: ["auth-config"], queryFn: () => call(api.GET("/api/v1/auth/config")) });
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (token) router.replace(next.startsWith("/") ? next : "/overview");
  }, [token, next, router]);

  async function devLogin(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const session = await call(api.POST("/api/v1/dev/session", { body: { email, displayName: name || undefined } }));
      tokenStore.set(session.accessToken, session.expiresAt);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  if (config.isPending) return <Spinner />;
  if (config.isError) return <ErrorState error={config.error} onRetry={() => config.refetch()} />;
  const { devLoginEnabled, oidcIssuer } = config.data;

  return (
    <div className="space-y-6">
      {oidcIssuer && (
        <Button variant="primary" className="w-full" onClick={() => void oidcManager(oidcIssuer).signinRedirect({ state: next })}>
          Continue with single sign-on
        </Button>
      )}
      {devLoginEnabled && (
        <form onSubmit={devLogin} className="space-y-4">
          {oidcIssuer && <div className="border-t border-border" />}
          <div className="rounded-md border border-warning/30 bg-warning-soft px-3 py-2 text-xs text-warning">
            Development sign-in is enabled on this server. It issues a session for any email address and must never be enabled in production.
          </div>
          <Field label="Email" htmlFor="email">
            <Input id="email" type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@company.com" />
          </Field>
          <Field label="Display name" htmlFor="name" hint="Optional">
            <Input id="name" value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" />
          </Field>
          <InlineError error={error} />
          <Button type="submit" variant={oidcIssuer ? "secondary" : "primary"} className="w-full" loading={busy}>
            Sign in
          </Button>
        </form>
      )}
      {!devLoginEnabled && !oidcIssuer && (
        <p className="text-[13px] text-fg-2">No sign-in method is configured on this server. Ask your administrator to configure an OIDC provider.</p>
      )}
    </div>
  );
}

export default function LoginPage() {
  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex items-center gap-2.5">
          <Logo className="size-7" />
          <div>
            <p className="text-sm font-semibold tracking-tight">QuantumForge</p>
            <p className="text-xs text-fg-3">Break your code. Prove your fix.</p>
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface p-6">
          <h1 className="mb-5 text-base font-semibold">Sign in</h1>
          <Suspense fallback={<Spinner />}>
            <LoginForm />
          </Suspense>
        </div>
      </div>
    </div>
  );
}
