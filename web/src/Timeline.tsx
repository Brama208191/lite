import { describeCall, describeResult, describeStart } from "./describe";
import type { RunEvent } from "./types";

const LABELS: Record<RunEvent["type"], string> = {
  RUN_CREATED: "Run created",
  LEASE_ACQUIRED: "Worker took the run",
  MODEL_RESPONDED: "Model decided",
  TOOL_STARTED: "Tool call",
  TOOL_COMPLETED: "Tool result",
  RUN_COMPLETED: "Done",
  RUN_FAILED: "Failed",
};

function seconds(ms: number) {
  return `${(ms / 1000).toFixed(1)}s`;
}

function toolNames(events: RunEvent[]) {
  const names = new Map<string, string>();
  for (const event of events) {
    if (event.type === "MODEL_RESPONDED") {
      for (const block of event.payload.reply?.content ?? []) {
        if (block.type === "tool_use") names.set(block.id, block.name);
      }
    }
  }
  return names;
}

function Summary({ event, previous, names }: { event: RunEvent; previous?: RunEvent; names: Map<string, string> }) {
  const p = event.payload;
  switch (event.type) {
    case "RUN_CREATED":
      return <p className="summary">{p.task}</p>;
    case "LEASE_ACQUIRED":
      if (p.token > 1 && previous) {
        const gap = new Date(event.createdAt).getTime() - new Date(previous.createdAt).getTime();
        return (
          <p className="summary">
            <strong>{p.workerId}</strong> took over {seconds(gap)} after the last step and picked up from the log.
            Anything the old worker tries to write now is rejected.
          </p>
        );
      }
      return (
        <p className="summary">
          <strong>{p.workerId}</strong> started the run.
        </p>
      );
    case "MODEL_RESPONDED": {
      const blocks: any[] = p.reply?.content ?? [];
      const text = blocks.filter((b) => b.type === "text").map((b) => b.text).join(" ");
      const calls = blocks.filter((b) => b.type === "tool_use");
      return (
        <div className="summary">
          {text && <p>{text}</p>}
          {calls.length > 0 && (
            <ul className="plan">
              {calls.map((call) => (
                <li key={call.id}>{describeCall(call.name, call.input ?? {})}</li>
              ))}
            </ul>
          )}
        </div>
      );
    }
    case "TOOL_STARTED":
      return (
        <p className="summary">
          {describeStart(p.name)}
          {p.attempt > 1 && <span className="tag warn">sent again after the crash, with the same key</span>}
        </p>
      );
    case "TOOL_COMPLETED":
      return (
        <p className={`summary ${p.isError ? "error" : ""}`}>{describeResult(names.get(p.toolUseId), p.content, p.isError)}</p>
      );
    case "RUN_COMPLETED":
      return <p className="summary">{p.answer}</p>;
    case "RUN_FAILED":
      return <p className="summary error">{p.reason}</p>;
  }
}

export function Timeline({ events, developer }: { events: RunEvent[]; developer: boolean }) {
  if (events.length === 0) {
    return <p className="muted">Waiting for the first step…</p>;
  }
  const start = new Date(events[0].createdAt).getTime();
  const names = toolNames(events);
  return (
    <ol className="timeline">
      {events.map((event, i) => {
        const takeover = event.type === "LEASE_ACQUIRED" && event.leaseToken > 1;
        return (
          <li key={event.seq} className={`event ${event.type.toLowerCase()} ${takeover ? "is-takeover" : ""}`}>
            <div className="event-head">
              <span className="seq">{event.seq}</span>
              <span className="type">{takeover ? "Another worker took over" : LABELS[event.type]}</span>
              {event.workerId && <span className="who">{event.workerId}</span>}
              <span className="time">+{seconds(new Date(event.createdAt).getTime() - start)}</span>
            </div>
            <Summary event={event} previous={events[i - 1]} names={names} />
            {developer && (
              <details>
                <summary>
                  {event.type} · lease token {event.leaseToken}
                  {event.payload.idempotencyKey ? ` · key ${String(event.payload.idempotencyKey).replace(event.runId, "run")}` : ""}
                </summary>
                <pre>{JSON.stringify(event.payload, null, 2)}</pre>
              </details>
            )}
          </li>
        );
      })}
    </ol>
  );
}
