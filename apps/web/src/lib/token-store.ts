/**
 * Holds the bearer token for the browser session. sessionStorage keeps it per
 * tab and clears it when the tab closes; nothing is written to cookies, so the
 * API needs no CSRF protection.
 */
const KEY = "qf.session";

type Listener = () => void;
const listeners = new Set<Listener>();

interface StoredSession {
  token: string;
  expiresAt: string | null;
}

function read(): StoredSession | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.sessionStorage.getItem(KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as StoredSession;
    if (session.expiresAt && Date.parse(session.expiresAt) <= Date.now()) {
      window.sessionStorage.removeItem(KEY);
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

export const tokenStore = {
  get(): string | null {
    return read()?.token ?? null;
  },
  set(token: string, expiresAt: string | null) {
    window.sessionStorage.setItem(KEY, JSON.stringify({ token, expiresAt } satisfies StoredSession));
    listeners.forEach((l) => l());
  },
  clear() {
    window.sessionStorage.removeItem(KEY);
    listeners.forEach((l) => l());
  },
  /** Called when the API rejects the token; the session provider sends the user to sign in. */
  expire() {
    this.clear();
  },
  subscribe(listener: Listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};
