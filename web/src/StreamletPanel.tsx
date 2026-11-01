import { useEffect, useState } from "react";
import { api } from "./api";
import type { StreamletStatus, Trigger } from "./types";

const FALLBACK_SYMBOLS = ["BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "ADA/USD", "DOGE/USD", "AVAX/USD", "DOT/USD", "LINK/USD", "LTC/USD"]
  .map((label, id) => ({ id, label }));

function pct(bps: number) {
  return `${(bps / 100).toFixed(2)}%`;
}

function ago(iso: string | null) {
  if (!iso) return "never";
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  return s < 60 ? `${s}s ago` : `${Math.floor(s / 60)}m ago`;
}

function host(url: string) {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

export function StreamletPanel({ onStarted, onError }: { onStarted: (id: string) => void; onError: (message: string) => void }) {
  const [status, setStatus] = useState<StreamletStatus | null>(null);
  const [symbol, setSymbol] = useState(0);
  const [move, setMove] = useState(-520);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const load = () => api.streamlet().then(setStatus).catch(() => {});
    load();
    const timer = setInterval(load, 3000);
    return () => clearInterval(timer);
  }, []);

  if (!status) return null;
  const symbols = status.symbols.length > 0 ? status.symbols : FALLBACK_SYMBOLS;
  const state = !status.enabled ? "off" : status.connected ? "live" : "reconnecting";

  async function send() {
    setBusy(true);
    try {
      const { id } = await api.simulateSpike(symbol, move);
      onStarted(id);
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel feed">
      <p className="hand">market alerts</p>
      <h2>Streamlet feed</h2>
      <p className="feed-state">
        <span className={`dot ${state}`} />
        {state === "off" && "Not connected. Set KEEL_STREAMLET_URL to watch a Streamlet engine."}
        {state === "live" && <>Watching {status.kind} price windows from <a href={status.url} target="_blank" rel="noreferrer">{host(status.url)}</a></>}
        {state === "reconnecting" && <>Reconnecting to {host(status.url)}{status.lastError ? ` (${status.lastError})` : ""}…</>}
      </p>
      {status.enabled && (
        <p className="muted small">
          {status.windowsSeen.toLocaleString()} windows read · last {ago(status.lastEventAt)} · {status.alertsLastHour} of {status.maxAlertsPerHour} alerts this hour
        </p>
      )}
      <p className="small rule">
        An alert starts an agent run when a coin moves at least {pct(status.rule.absoluteBps)} in one window, or at least{" "}
        {pct(status.rule.minBps)} and {status.rule.ratio}× its average over the previous {status.rule.history} windows. Moves of{" "}
        {pct(status.tradeAboveBps)} or more also get a paper hedge trade.
      </p>
      <details className="test-spike">
        <summary>Send a test spike</summary>
        <div className="test-row">
          <select value={symbol} onChange={(e) => setSymbol(Number(e.target.value))} aria-label="Coin">
            {symbols.map((s) => (
              <option key={s.id} value={s.id}>{s.label}</option>
            ))}
          </select>
          <select value={move} onChange={(e) => setMove(Number(e.target.value))} aria-label="Move">
            {[-520, -210, 180, 480].map((bps) => (
              <option key={bps} value={bps}>{bps > 0 ? "+" : "−"}{pct(Math.abs(bps))}</option>
            ))}
          </select>
        </div>
        <button className="wide" onClick={send} disabled={busy}>{busy ? "Sending…" : "Send through the detector"}</button>
      </details>
    </section>
  );
}

export function TriggerNote({ trigger, source }: { trigger: Trigger; source: string }) {
  const where = trigger.streamletUrl ? <a href={trigger.streamletUrl} target="_blank" rel="noreferrer">Streamlet</a> : "Streamlet";
  if (trigger.type === "spike") {
    const direction = (trigger.changeBps ?? 0) >= 0 ? "jumped" : "dropped";
    return (
      <div className="trigger">
        <span className="hand">{source === "streamlet-test" ? "test spike" : "from Streamlet"}</span>
        <p>
          {trigger.label} {direction} {pct(Math.abs(trigger.changeBps ?? 0))} in one {trigger.kind} window on {where}
          {trigger.baselineBps ? `, after averaging ${pct(trigger.baselineBps)} per window` : ""}.
          {trigger.hedge ? " Big enough for a hedge trade." : ""}
        </p>
      </div>
    );
  }
  return (
    <div className="trigger">
      <span className="hand">from Streamlet</span>
      <p>{trigger.type === "engine-down" ? <>{where}'s engine went down.</> : <>{where}'s verifier reported mismatched windows.</>}</p>
    </div>
  );
}
