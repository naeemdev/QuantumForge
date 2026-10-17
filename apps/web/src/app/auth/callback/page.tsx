"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, call } from "@/lib/api";
import { completeOidcSignIn } from "@/lib/oidc";
import { ErrorState, Spinner } from "@/components/ui/states";

export default function OidcCallback() {
  const router = useRouter();
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    (async () => {
      try {
        const config = await call(api.GET("/api/v1/auth/config"));
        if (!config.oidcIssuer) throw new Error("Single sign-on is not configured on this server");
        await completeOidcSignIn(config.oidcIssuer);
        router.replace("/overview");
      } catch (e) {
        setError(e);
      }
    })();
  }, [router]);

  return <div className="mx-auto mt-24 max-w-md px-4">{error ? <ErrorState error={error} /> : <Spinner label="Completing sign-in" />}</div>;
}
