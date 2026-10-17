"use client";

import { useRouter } from "next/navigation";
import { useSession } from "@/lib/session";
import { ThemeSwitcher } from "@/components/theme";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { PageHeader } from "@/components/ui/page-header";

export default function SettingsPage() {
  const { me, signOut } = useSession();
  const router = useRouter();
  const rows: [string, string][] = [
    ["Name", me?.displayName ?? "—"],
    ["Email", me?.email ?? "—"],
    ["Signed in with", me?.authMethod === "DEV_LOGIN" ? "Development login" : me?.authMethod === "OIDC" ? "Single sign-on" : "API token"],
    ["User ID", me?.id ?? "—"],
  ];
  return (
    <>
      <PageHeader title="Settings" />
      <div className="max-w-2xl space-y-6">
        <Card>
          <CardHeader title="Profile" description="Managed by your identity provider." />
          <dl className="divide-y divide-border text-[13px]">
            {rows.map(([k, v]) => (
              <div key={k} className="flex justify-between gap-4 px-4 py-2.5">
                <dt className="text-fg-3">{k}</dt>
                <dd className={k === "User ID" ? "font-mono text-xs text-fg-2" : "text-fg"}>{v}</dd>
              </div>
            ))}
          </dl>
        </Card>
        <Card>
          <CardHeader title="Appearance" />
          <div className="flex items-center justify-between px-4 py-3 text-[13px]">
            <span className="text-fg-2">Theme</span>
            <ThemeSwitcher />
          </div>
        </Card>
        <Card>
          <CardHeader title="Organizations" description={`You belong to ${me?.memberships.length ?? 0}.`} actions={<Button size="sm" onClick={() => router.push("/onboarding")}>Create or join</Button>} />
        </Card>
        <Button variant="danger" onClick={signOut}>Sign out</Button>
      </div>
    </>
  );
}
