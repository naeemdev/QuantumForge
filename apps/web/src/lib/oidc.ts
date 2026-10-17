import { UserManager, WebStorageStateStore } from "oidc-client-ts";
import { OIDC_CLIENT_ID, OIDC_SCOPE } from "./config";
import { tokenStore } from "./token-store";

let manager: UserManager | null = null;

/** Browser OIDC client (authorization code flow with PKCE), configured from the API's /auth/config. */
export function oidcManager(issuer: string): UserManager {
  if (!manager) {
    manager = new UserManager({
      authority: issuer,
      client_id: OIDC_CLIENT_ID,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: `${window.location.origin}/login`,
      response_type: "code",
      scope: OIDC_SCOPE,
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    });
  }
  return manager;
}

export async function completeOidcSignIn(issuer: string): Promise<void> {
  const user = await oidcManager(issuer).signinRedirectCallback();
  // The API validates access tokens issued for it; ID tokens are not sent.
  tokenStore.set(user.access_token, user.expires_at ? new Date(user.expires_at * 1000).toISOString() : null);
}

export async function oidcSignOut(): Promise<void> {
  if (manager) await manager.removeUser();
}
