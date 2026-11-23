export type ApiError = Error & { status?: number; body?: any };

export interface ApiOptions {
  method?: string;
  body?: unknown;
  /** Extra headers, e.g. Idempotency-Key for order submission. */
  headers?: Record<string, string>;
}

/** Central TypeScript client for the Java Manager API (same-origin cookies). */
export async function api<T>(path: string, init?: ApiOptions): Promise<T> {
  const headers: Record<string, string> = {
    Accept: "application/json",
    ...(init?.headers ?? {}),
  };
  let body: string | undefined;
  if (init?.body !== undefined) {
    // Browser writes require the marker header, JSON, same origin (Router.BROWSER_WRITE).
    headers["Content-Type"] = "application/json";
    headers["X-Stash-Manager"] = "1";
    body = JSON.stringify(init.body);
  }
  let res: Response;
  try {
    res = await fetch(path, {
      method: init?.method ?? (init?.body !== undefined ? "POST" : undefined),
      headers,
      body,
      credentials: "same-origin",
    });
  } catch (e) {
    throw networkError(e);
  }
  if (res.status === 401) {
    window.dispatchEvent(new CustomEvent("wesort:unauthorized"));
    const err = new Error("Session expired, please log in again.") as ApiError;
    err.status = 401;
    throw err;
  }
  if (res.status === 403) {
    let json: any = null;
    try {
      json = await res.json();
    } catch {
      json = null;
    }
    const mustChange = !!json?.mustChange;
    const err = new Error(
      mustChange
        ? "You must change your password before doing that."
        : (json?.error ?? "You don't have permission to do that."),
    ) as ApiError;
    err.status = 403;
    err.body = json;
    throw err;
  }
  const text = await res.text();
  let json: any = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (!res.ok) {
    const err = new Error(
      (json && (json.error || json.message)) ||
        `Request failed (HTTP ${res.status}).`,
    ) as ApiError;
    err.status = res.status;
    err.body = json;
    throw err;
  }
  return json as T;
}

function networkError(e: unknown): ApiError {
  const err = new Error(
    "Cannot reach the Manager. Check the connection and retry.",
  ) as ApiError;
  err.status = 0;
  (err as any).cause = e;
  return err;
}

/** Idempotency key for order submission (HTTP Idempotency-Key header; the server replays by receipt). */
export function idempotencyKey(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto)
    return crypto.randomUUID();
  return `id-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

void idempotencyKey;
