// Typed fetch wrapper. Reads base URL and API key from localStorage.
// All API calls go through `apiFetch` — swap this to use the TypeScript SDK
// when it ships in V2 (one-file change, zero impact on consumers).

const LS_API_URL = "fg_api_url";
const LS_API_KEY = "fg_api_key";

export function getApiUrl(): string {
  // In dev the Vite proxy forwards /api/* to the server — use empty string (same origin).
  // In production, fall back to whatever the operator saved in Settings.
  return localStorage.getItem(LS_API_URL) ?? "";
}

export function getApiKey(): string {
  return localStorage.getItem(LS_API_KEY) ?? "";
}

export function saveSettings(url: string, key: string): void {
  localStorage.setItem(LS_API_URL, url.replace(/\/$/, "")); // strip trailing slash
  localStorage.setItem(LS_API_KEY, key);
}

export function isConfigured(): boolean {
  return getApiKey().length > 0;
}

// Recent budget IDs (for sidebar navigation)
const LS_RECENT = "fg_recent_budgets";
const MAX_RECENT = 10;

export function getRecentBudgets(): string[] {
  try {
    return JSON.parse(localStorage.getItem(LS_RECENT) ?? "[]");
  } catch {
    return [];
  }
}

export function pushRecentBudget(id: string): void {
  const list = getRecentBudgets().filter((x) => x !== id);
  list.unshift(id);
  localStorage.setItem(LS_RECENT, JSON.stringify(list.slice(0, MAX_RECENT)));
}

export function removeRecentBudget(id: string): void {
  const list = getRecentBudgets().filter((x) => x !== id);
  localStorage.setItem(LS_RECENT, JSON.stringify(list));
}

// ---------------------------------------------------------------------------

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: unknown,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export async function apiFetch<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const url = `${getApiUrl()}${path}`;
  const resp = await fetch(url, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "X-Agent-Budget-Key": getApiKey(),
      ...options?.headers,
    },
  });

  if (!resp.ok) {
    let body: unknown;
    try {
      body = await resp.json();
    } catch {
      body = await resp.text();
    }
    throw new ApiError(resp.status, body, `HTTP ${resp.status}: ${path}`);
  }

  // 204 No Content
  if (resp.status === 204) return undefined as T;

  return resp.json() as Promise<T>;
}
