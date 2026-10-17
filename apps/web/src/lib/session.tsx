"use client";

import { createContext, useCallback, useContext, useMemo, useState, useSyncExternalStore, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { MeResponse, MembershipView } from "@quantumforge/contracts";
import { api, call } from "./api";
import { tokenStore } from "./token-store";
import { oidcSignOut } from "./oidc";

interface SessionValue {
  token: string | null;
  me: MeResponse | undefined;
  loading: boolean;
  /** The organization the UI is scoped to. Every query is keyed by it. */
  organization: MembershipView | undefined;
  setOrganizationId: (id: string) => void;
  can: (permission: string) => boolean;
  signOut: () => void;
}

const SessionContext = createContext<SessionValue | null>(null);
const ORG_KEY = "qf.activeOrganization";

function readStoredOrg(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(ORG_KEY);
  } catch {
    return null;
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const token = useSyncExternalStore(tokenStore.subscribe, tokenStore.get, () => null);
  const queryClient = useQueryClient();
  const [orgId, setOrgId] = useState<string | null>(readStoredOrg);

  const meQuery = useQuery({
    queryKey: ["me", token],
    queryFn: () => call(api.GET("/api/v1/me")),
    enabled: token != null,
    staleTime: 60_000,
  });

  const organization = useMemo(() => {
    const memberships = meQuery.data?.memberships ?? [];
    return memberships.find((m) => m.organizationId === orgId) ?? memberships[0];
  }, [meQuery.data, orgId]);

  const setOrganizationId = useCallback(
    (id: string) => {
      setOrgId(id);
      try {
        window.localStorage.setItem(ORG_KEY, id);
      } catch {
        // Preference only; the first membership is used when storage is unavailable.
      }
      queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== "me" });
    },
    [queryClient],
  );

  const can = useCallback((permission: string) => organization?.permissions.includes(permission) ?? false, [organization]);

  const signOut = useCallback(() => {
    tokenStore.clear();
    queryClient.clear();
    void oidcSignOut();
  }, [queryClient]);

  const value = useMemo<SessionValue>(
    () => ({ token, me: meQuery.data, loading: token != null && meQuery.isPending, organization, setOrganizationId, can, signOut }),
    [token, meQuery.data, meQuery.isPending, organization, setOrganizationId, can, signOut],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error("useSession must be used inside SessionProvider");
  return value;
}

/** The active organization id; components under the app shell can rely on it. */
export function useOrgId(): string {
  const { organization } = useSession();
  if (!organization) throw new Error("No active organization");
  return organization.organizationId;
}
