export type EventType =
  | "RUN_CREATED"
  | "LEASE_ACQUIRED"
  | "MODEL_RESPONDED"
  | "TOOL_STARTED"
  | "TOOL_COMPLETED"
  | "RUN_COMPLETED"
  | "RUN_FAILED";

export interface RunEvent {
  runId: string;
  seq: number;
  type: EventType;
  payload: Record<string, any>;
  leaseToken: number;
  workerId: string | null;
  createdAt: string;
}

export interface RunSummary {
  id: string;
  task: string;
  model: string;
  status: "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";
  source: "manual" | "streamlet" | "streamlet-test";
  trigger: Trigger | null;
  createdAt: string;
  updatedAt: string;
  events: number;
  holder: string | null;
}

export interface WorkerView {
  id: string;
  startedAt: string;
  heartbeatAt: string;
  alive: boolean;
  leases: number;
}

export interface Effect {
  key: string;
  kind: "email" | "charge" | "trade";
  reference: string;
  appliedAt: string;
  response: Record<string, any>;
  requests: number;
  replays: number;
}

export interface Info {
  models: string[];
  anthropicModel: string;
  leaseTtlMs: number;
  stableKeys: boolean;
}

export interface Trigger {
  type: "spike" | "engine-down" | "mismatch";
  ref: string;
  label?: string;
  kind?: string;
  changeBps?: number;
  baselineBps?: number;
  reason?: string;
  hedge?: boolean;
  simulated: boolean;
  streamletUrl: string;
}

export interface StreamletStatus {
  enabled: boolean;
  url: string;
  kind: string;
  connected: boolean;
  lastEventAt: string | null;
  windowsSeen: number;
  alertsLastHour: number;
  maxAlertsPerHour: number;
  lastError: string | null;
  rule: { history: number; ratio: number; minBps: number; absoluteBps: number };
  tradeAboveBps: number;
  cooldownSeconds: number;
  symbols: { id: number; label: string }[];
}
