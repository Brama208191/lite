import type { Effect, Info, RunEvent, RunSummary, WorkerView } from "./types";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.message ?? body.error ?? `HTTP ${response.status}`);
  }
  return body as T;
}

export const api = {
  info: () => request<Info>("/api/info"),
  runs: () => request<RunSummary[]>("/api/runs"),
  run: (id: string) => request<{ run: RunSummary; events: RunEvent[] }>(`/api/runs/${id}`),
  create: (task: string, model: string) =>
    request<{ id: string }>("/api/runs", { method: "POST", body: JSON.stringify({ task, model }) }),
  kill: (id: string) => request<{ worker: string }>(`/api/runs/${id}/kill`, { method: "POST" }),
  effects: (id: string) => request<{ effects: Effect[] }>(`/api/runs/${id}/effects`),
  workers: () => request<WorkerView[]>("/api/workers"),
};

export function streamEvents(id: string, onEvent: (event: RunEvent) => void, onState: (open: boolean) => void) {
  const source = new EventSource(`/api/runs/${id}/stream`);
  source.addEventListener("event", (message) => onEvent(JSON.parse((message as MessageEvent).data)));
  source.onopen = () => onState(true);
  source.onerror = () => onState(false);
  return () => source.close();
}
