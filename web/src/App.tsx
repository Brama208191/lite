import { useCallback, useEffect, useMemo, useState } from "react";
import { api, streamEvents } from "./api";
import { money } from "./describe";
import { SCENARIOS } from "./scenarios";
import { StreamletPanel, TriggerNote } from "./StreamletPanel";
import { Timeline } from "./Timeline";
import type { Effect, Info, RunEvent, RunSummary, WorkerView } from "./types";

function useHashRun(): [string | null, (id: string) => void] {
  const read = () => new URLSearchParams(window.location.hash.slice(1)).get("run");
  const [id, setId] = useState<string | null>(read);
  useEffect(() => {
    const onHash = () => setId(read());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  return [id, (next: string) => (window.location.hash = `run=${next}`)];
}

function useDeveloperView(): [boolean, (on: boolean) => void] {
  const [on, setOn] = useState(() => {
    try {
      return localStorage.getItem("keel.developer") === "1";
    } catch {
      return false;
    }
  });
  return [
    on,
    (next: boolean) => {
      setOn(next);
      try {
        localStorage.setItem("keel.developer", next ? "1" : "0");
      } catch {
        return;
      }
    },
  ];
}

const KIND_LABELS: Record<Effect["kind"], string> = { email: "email", charge: "card charge", trade: "paper trade", incident: "page" };

function effectTitle(effect: Effect) {
  switch (effect.kind) {
    case "charge":
      return `Order ${effect.reference}`;
    case "incident":
      return `${effect.response.severity}: ${effect.response.summary}`;
    case "trade":
      return `${effect.response.side} ${money(effect.response.notional_cents, "usd")} of ${effect.response.symbol} for ${effect.reference}`;
    default:
      return effect.reference.split(" | ")[1] ?? effect.reference;
  }
}

function ago(iso: string) {
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  return s < 60 ? `${s}s ago` : s < 3600 ? `${Math.floor(s / 60)}m ago` : `${Math.floor(s / 3600)}h ago`;
}

export function App() {
  const [info, setInfo] = useState<Info | null>(null);
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [workers, setWorkers] = useState<WorkerView[]>([]);
  const [selected, select] = useHashRun();
  const [developer, setDeveloper] = useDeveloperView();
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [effects, setEffects] = useState<Effect[]>([]);
  const [connected, setConnected] = useState(false);
  const [model, setModel] = useState("fake");
  const [scenarioId, setScenarioId] = useState(SCENARIOS[0].id);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ text: string; tone: "info" | "bad" } | null>(null);
  const [killedAt, setKilledAt] = useState<number | null>(null);
  const [, tick] = useState(0);

  useEffect(() => {
    api.info().then(setInfo).catch(() => setNotice({ text: "The API is not reachable.", tone: "bad" }));
  }, []);

  useEffect(() => {
    const load = () => {
      api.runs().then(setRuns).catch(() => {});
      api.workers().then(setWorkers).catch(() => {});
      tick((n) => n + 1);
    };
    load();
    const timer = setInterval(load, 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    setEvents([]);
    setEffects([]);
    setKilledAt(null);
    if (!selected) return;
    return streamEvents(
      selected,
      (event) =>
        setEvents((current) =>
          current.some((e) => e.seq === event.seq) ? current : [...current, event].sort((a, b) => a.seq - b.seq),
        ),
      setConnected,
    );
  }, [selected]);

  const run = runs.find((r) => r.id === selected);
  const last = events[events.length - 1];
  const terminal = last?.type === "RUN_COMPLETED" || last?.type === "RUN_FAILED";

  const refreshEffects = useCallback(() => {
    if (selected) api.effects(selected).then((r) => setEffects(r.effects)).catch(() => {});
  }, [selected]);

  useEffect(() => {
    if (last?.type === "TOOL_COMPLETED" || terminal) refreshEffects();
  }, [last?.seq, terminal, refreshEffects]);

  useEffect(() => {
    if (last?.type === "LEASE_ACQUIRED" && last.leaseToken > 1) setKilledAt(null);
  }, [last?.seq]);

  const stats = useMemo(() => {
    const takeovers = events.filter((e) => e.type === "LEASE_ACQUIRED" && e.leaseToken > 1).length;
    const resends = events.filter((e) => e.type === "TOOL_STARTED" && e.payload.attempt > 1).length;
    const requests = effects.reduce((n, e) => n + e.requests, 0);
    return { takeovers, resends, requests };
  }, [events, effects]);

  async function start() {
    setBusy(true);
    setNotice(null);
    try {
      const { id } = await api.create(scenario.task(), model);
      select(id);
    } catch (e) {
      setNotice({ text: (e as Error).message, tone: "bad" });
    } finally {
      setBusy(false);
    }
  }

  async function kill() {
    if (!selected) return;
    try {
      const { worker } = await api.kill(selected);
      setKilledAt(Date.now());
      setNotice({
        text: `${worker} was killed mid-run with no cleanup, like kill -9. Its lease runs out in ${(info?.leaseTtlMs ?? 3000) / 1000}s, then another worker picks the run up.`,
        tone: "info",
      });
    } catch (e) {
      setNotice({ text: (e as Error).message, tone: "bad" });
    }
  }

  const waiting = killedAt !== null && !terminal;
  const scenarios = SCENARIOS.filter((s) => !s.claudeOnly || model === "anthropic");
  const scenario = scenarios.find((s) => s.id === scenarioId) ?? scenarios[0];

  return (
    <div className="shell">
      <header className="site-bar">
        <span className="wordmark">keel lite</span>
        <span className="tagline muted">a crash-safe agent runtime</span>
        <span className="spacer" />
        <div className="workers" aria-label="Workers">
          {workers.map((w) => (
            <span key={w.id} className={`worker ${w.alive ? "alive" : "dead"} ${run?.holder === w.id ? "holder" : ""}`}
                  title={`started ${ago(w.startedAt)}, last heartbeat ${ago(w.heartbeatAt)}`}>
              <span className="dot" />
              {w.id}
              {!w.alive && <span className="hand-note">down</span>}
            </span>
          ))}
        </div>
      </header>

      <main className="layout">
        <aside className="side">
          <section className="panel">
            <p className="hand">try it</p>
            <h2>Start a run</h2>
            <label className="field">
              <span>Model</span>
              <select value={model} onChange={(e) => { setModel(e.target.value); setScenarioId(SCENARIOS[0].id); }}>
                <option value="fake">Scripted model (free, always the same)</option>
                {info?.models.includes("anthropic") && <option value="anthropic">Claude ({info.anthropicModel})</option>}
              </select>
            </label>
            <label className="field">
              <span>Scenario</span>
              <select value={scenario.id} onChange={(e) => setScenarioId(e.target.value)}>
                {scenarios.map((s) => (
                  <option key={s.id} value={s.id}>{s.label}</option>
                ))}
              </select>
            </label>
            <p className="scenario-hint">{scenario.hint}</p>
            <button className="primary wide" onClick={start} disabled={busy}>
              {busy ? "Starting…" : "Start run"}
            </button>
          </section>

          <StreamletPanel onStarted={select} onError={(text) => setNotice({ text, tone: "bad" })} />

          <section className="panel">
            <h2>Recent runs</h2>
            <ul className="runs">
              {runs.length === 0 && <li className="muted">No runs yet.</li>}
              {runs.slice(0, 8).map((r) => (
                <li key={r.id}>
                  <button className={`run-item ${r.id === selected ? "active" : ""}`} onClick={() => select(r.id)}>
                    <span className="run-task">{r.task}</span>
                    <span className="run-meta">
                      <span className={`status ${r.status.toLowerCase()}`}>{r.status.toLowerCase()}</span>
                      {r.source !== "manual" && <span className="source">{r.source === "streamlet" ? "alert" : "test"}</span>}
                      {ago(r.createdAt)}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        </aside>

        <section className="main">
          {notice && <div className={`notice ${notice.tone}`} role="status">{notice.text}</div>}
          {!selected && (
            <div className="panel hero">
              <p className="hand">how it works</p>
              <h1>Kill the worker. Nothing happens twice.</h1>
              <ol>
                <li>Start a run. The agent logs a note, emails the customer and charges the card.</li>
                <li>While it is working, press <strong>Kill the worker</strong>.</li>
                <li>Another worker takes over from the saved log. The email and the charge still happen exactly one
                  time each, which the provider panel confirms.</li>
              </ol>
            </div>
          )}
          {selected && (
            <>
              <div className="panel run-head">
                <div className="run-title">
                  <h2>{run?.task ?? events[0]?.payload.task ?? "Loading…"}</h2>
                  <p className="muted">
                    <span className={`status ${(run?.status ?? "pending").toLowerCase()}`}>{(run?.status ?? "…").toLowerCase()}</span>{" "}
                    {run?.holder ? <>running on <strong>{run.holder}</strong></> : terminal ? "finished" : "waiting for a worker"}
                    {!connected && " · reconnecting…"}
                  </p>
                  {run?.trigger && <TriggerNote trigger={run.trigger} source={run.source} />}
                </div>
                <button className="danger" onClick={kill} disabled={terminal || !run?.holder}>
                  Kill the worker
                </button>
              </div>

              {waiting && (
                <div className="notice waiting">
                  <span className="hand">hold on</span> worker down for {((Date.now() - (killedAt ?? Date.now())) / 1000).toFixed(0)}s, waiting for
                  its lease to run out so another worker can take over…
                </div>
              )}

              <div className="stats">
                <div><strong>{stats.takeovers}</strong><span>takeovers</span></div>
                <div><strong>{stats.resends}</strong><span>calls sent again</span></div>
                <div><strong>{effects.length}</strong><span>effects applied</span></div>
                <div><strong>{stats.requests}</strong><span>requests received</span></div>
              </div>

              <div className="columns">
                <section className="panel">
                  <div className="panel-head">
                    <h2>What happened</h2>
                    <label className="toggle">
                      <input type="checkbox" checked={developer} onChange={(e) => setDeveloper(e.target.checked)} />
                      Developer view
                    </label>
                  </div>
                  <Timeline events={events} developer={developer} />
                </section>
                <section className="panel effects">
                  <h2>At the provider</h2>
                  <p className="muted small">
                    A separate service standing in for the email, payment, pager and broker companies. It counts every request it gets.
                  </p>
                  {effects.length === 0 && <p className="muted">Nothing yet.</p>}
                  <ul>
                    {effects.map((effect) => (
                      <li key={effect.key}>
                        <span className={`kind ${effect.kind}`}>{KIND_LABELS[effect.kind]}</span>
                        <p>{effectTitle(effect)}</p>
                        <p className="small">
                          happened <strong className="once">once</strong>
                          {effect.requests > 1
                            ? `, asked ${effect.requests} times (the repeat got the original answer)`
                            : ""}
                        </p>
                        {developer && <p className="muted small mono">key {effect.key.replace(selected, "run")} · {effect.response.id}</p>}
                      </li>
                    ))}
                  </ul>
                </section>
              </div>
            </>
          )}
        </section>
      </main>
      <footer className="foot muted small">
        Emails and charges happen once because every call carries a key the provider remembers. Notes happen once because the
        note and its log entry are saved together.
      </footer>
    </div>
  );
}
