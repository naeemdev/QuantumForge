import createClient, { type Middleware } from "openapi-fetch";
import type { ApiErrorBody, paths } from "@quantumforge/contracts";
import { API_URL } from "./config";
import { tokenStore } from "./token-store";

/** A failed API call, carrying the server's error envelope. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | null;
  readonly fieldErrors: { field: string; message: string }[];
  readonly details: Record<string, unknown>;

  constructor(status: number, body: ApiErrorBody | undefined) {
    super(body?.error.message ?? `Request failed with status ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.code = body?.error.code ?? "unknown";
    this.requestId = body?.error.requestId ?? null;
    this.fieldErrors = body?.error.fieldErrors ?? [];
    this.details = body?.error.details ?? {};
  }
}

const auth: Middleware = {
  onRequest({ request }) {
    const token = tokenStore.get();
    if (token) request.headers.set("Authorization", `Bearer ${token}`);
    return request;
  },
  onResponse({ response }) {
    if (response.status === 401 && tokenStore.get()) tokenStore.expire();
    return response;
  },
};

export const api = createClient<paths>({ baseUrl: API_URL });
api.use(auth);

/**
 * Unwraps an openapi-fetch result: returns data or throws ApiError. Keeps every
 * call site to one line and every error in the same shape.
 */
export async function call<T>(promise: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await promise;
  if (!response.ok) throw new ApiError(response.status, error as ApiErrorBody | undefined);
  return data as T;
}

/** Authenticated raw fetch, for downloads and streams openapi-fetch does not model. */
export async function authorizedFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  const token = tokenStore.get();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(`${API_URL}${path}`, { ...init, headers });
  if (response.status === 401 && token) tokenStore.expire();
  if (!response.ok) {
    const body = (await response.json().catch(() => undefined)) as ApiErrorBody | undefined;
    throw new ApiError(response.status, body);
  }
  return response;
}

/** Multipart upload with progress (fetch has no upload progress events). */
export function uploadWithProgress<T>(path: string, form: FormData, onProgress: (fraction: number) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${API_URL}${path}`);
    const token = tokenStore.get();
    if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
    xhr.onerror = () => reject(new ApiError(0, undefined));
    xhr.onload = () => {
      let body: unknown;
      try {
        body = JSON.parse(xhr.responseText);
      } catch {
        body = undefined;
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(body as T);
      else reject(new ApiError(xhr.status, body as ApiErrorBody | undefined));
    };
    xhr.send(form);
  });
}
