"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { Activity, Building2, ChevronsUpDown, FolderGit2, LayoutDashboard, LogOut, Map, Settings } from "lucide-react";
import { useSession } from "@/lib/session";
import { Spinner } from "./ui/states";
import { ThemeSwitcher } from "./theme";
import { cn } from "./ui/cn";

const primary = [
  { href: "/overview", label: "Overview", icon: LayoutDashboard },
  { href: "/projects", label: "Projects", icon: FolderGit2 },
  { href: "/test-runs", label: "Test Runs", icon: Activity },
];

const admin = [
  { href: "/organization", label: "Organization", icon: Building2 },
  { href: "/settings", label: "Settings", icon: Settings },
];

/** Modules that exist in the product plan but have no backend yet. Shown as planned, never as working. */
const planned = ["Code Intelligence", "Break My Code", "Findings", "Repair Verification", "Code Graph", "Reports"];

function NavLink({ href, label, icon: Icon }: { href: string; label: string; icon: typeof Activity }) {
  const pathname = usePathname();
  const active = pathname === href || pathname.startsWith(`${href}/`);
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      className={cn(
        "flex h-8 items-center gap-2.5 rounded-md px-2 text-[13px] transition-colors",
        active ? "bg-surface-2 font-medium text-fg" : "text-fg-2 hover:bg-surface-hover hover:text-fg",
      )}
    >
      <Icon className="size-4 shrink-0" aria-hidden />
      {label}
    </Link>
  );
}

function OrganizationSwitcher() {
  const { me, organization, setOrganizationId } = useSession();
  const memberships = me?.memberships ?? [];
  return (
    <label className="relative block">
      <span className="sr-only">Organization</span>
      <select
        value={organization?.organizationId}
        onChange={(e) => setOrganizationId(e.target.value)}
        className="h-9 w-full appearance-none truncate rounded-md border border-border bg-surface pl-2.5 pr-7 text-[13px] font-medium text-fg hover:border-border-strong"
      >
        {memberships.map((m) => (
          <option key={m.organizationId} value={m.organizationId}>
            {m.organizationName}
          </option>
        ))}
      </select>
      <ChevronsUpDown className="pointer-events-none absolute right-2 top-2.5 size-4 text-fg-3" aria-hidden />
    </label>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const { token, me, loading, organization, signOut } = useSession();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (!token) router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    else if (me && me.memberships.length === 0) router.replace("/onboarding");
  }, [token, me, router, pathname]);

  if (!token || loading || !me || !organization) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner />
      </div>
    );
  }

  return (
    <div className="flex min-h-screen">
      <aside className="sticky top-0 flex h-screen w-60 shrink-0 flex-col border-r border-border bg-surface">
        <div className="flex h-12 items-center gap-2 border-b border-border px-4">
          <Logo />
          <span className="text-[13px] font-semibold tracking-tight">QuantumForge</span>
        </div>
        <div className="px-3 pt-3">
          <OrganizationSwitcher />
        </div>
        <nav className="flex-1 space-y-5 overflow-y-auto px-3 py-4" aria-label="Main">
          <div className="space-y-0.5">
            {primary.map((item) => (
              <NavLink key={item.href} {...item} />
            ))}
          </div>
          <div className="space-y-0.5">
            <p className="px-2 pb-1 text-[11px] font-medium uppercase tracking-wide text-fg-3">Administration</p>
            {admin.map((item) => (
              <NavLink key={item.href} {...item} />
            ))}
          </div>
          <div className="space-y-0.5">
            <p className="px-2 pb-1 text-[11px] font-medium uppercase tracking-wide text-fg-3">Planned</p>
            {planned.map((label) => (
              <Link key={label} href="/roadmap" className="flex h-7 items-center justify-between rounded-md px-2 text-[13px] text-fg-3 hover:bg-surface-hover">
                {label}
                <span className="text-[10px] uppercase tracking-wide">Soon</span>
              </Link>
            ))}
            <NavLink href="/roadmap" label="Roadmap" icon={Map} />
          </div>
        </nav>
        <div className="border-t border-border p-3">
          <div className="flex items-center justify-between gap-2">
            <div className="min-w-0">
              <p className="truncate text-[13px] font-medium">{me.displayName ?? me.email}</p>
              <p className="truncate text-xs text-fg-3">{me.email}</p>
            </div>
            <button onClick={signOut} className="rounded p-1.5 text-fg-3 hover:bg-surface-hover hover:text-fg" aria-label="Sign out" title="Sign out">
              <LogOut className="size-4" />
            </button>
          </div>
          <div className="mt-3">
            <ThemeSwitcher />
          </div>
        </div>
      </aside>
      <main className="min-w-0 flex-1">
        <div className="mx-auto max-w-6xl px-8 py-8">{children}</div>
      </main>
    </div>
  );
}

export function Logo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={cn("size-5", className)} aria-hidden>
      <rect x="2" y="2" width="20" height="20" rx="5" fill="var(--text)" />
      <path d="M8 8h5.5a2.5 2.5 0 0 1 0 5H11l3.5 3.5" stroke="var(--surface)" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
