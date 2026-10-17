export class HttpError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "HttpError";
    this.status = status;
  }
}

export interface RetryOptions {
  /** Additional attempts after the first one. */
  retries: number;
  baseDelayMs: number;
  sleep?: (ms: number) => Promise<void>;
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Calls fn, retrying failures with exponential backoff.
 *
 * SEEDED DEFECT D4 (incorrect retry behaviour): every error is retried,
 * including 4xx client errors that will fail identically on every attempt.
 * Retrying a rejected payment (402) or a validation error (422) multiplies
 * load on the downstream service and delays the real error.
 */
export async function withRetry<T>(fn: () => Promise<T>, options: RetryOptions): Promise<T> {
  const sleep = options.sleep ?? defaultSleep;
  let attempt = 0;
  for (;;) {
    try {
      return await fn();
    } catch (error) {
      if (attempt >= options.retries) throw error;
      await sleep(options.baseDelayMs * 2 ** attempt);
      attempt++;
    }
  }
}
