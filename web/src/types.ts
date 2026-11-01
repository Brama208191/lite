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
  kind: "email" | "charge";
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
