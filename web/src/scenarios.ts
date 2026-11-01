export interface Scenario {
  id: string;
  label: string;
  hint: string;
  claudeOnly?: boolean;
  task: () => string;
}

const CUSTOMERS = ["Ada Lovelace", "Grace Hopper", "Alan Turing", "Katherine Johnson", "Edsger Dijkstra"];

function pick<T>(items: T[]) {
  return items[Math.floor(Math.random() * items.length)];
}

function clock(withSeconds: boolean) {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(now.getUTCHours())}:${pad(now.getUTCMinutes())}${withSeconds ? `:${pad(now.getUTCSeconds())}` : ":00"} UTC`;
}

function stamp() {
  return Math.floor(Date.now() / 1000);
}

export const SCENARIOS: Scenario[] = [
  {
    id: "order",
    label: "Process a customer order",
    hint: "Logs the order, emails the customer, charges the card.",
    task: () => `Process order ORD-${1000 + Math.floor(Math.random() * 9000)} for ${pick(CUSTOMERS)}`,
  },
  {
    id: "drop",
    label: "Market alert: BTC drops 5.2%",
    hint: "Logs it, pages the on-call team, places a $500 paper hedge.",
    task: () =>
      `ALERT TEST-BTC-${stamp()}: BTC/USD fell 5.20% in the 1m window ending ${clock(false)} (close $58,104.50). ` +
      "The previous 5 windows moved 0.06% on average. Investigate: log it to the alerts notes and page the on-call team, " +
      "then place a $500.00 paper hedge trade (buy BTC/USD).",
  },
  {
    id: "jump",
    label: "Market alert: SOL jumps 1.8%",
    hint: "Logs it and pages the on-call team. Too small for a trade.",
    task: () =>
      `ALERT TEST-SOL-${stamp()}: SOL/USD rose 1.80% in the 1m window ending ${clock(false)} (close $178.42). ` +
      "The previous 5 windows moved 0.11% on average. Investigate: log it to the alerts notes and page the on-call team.",
  },
  {
    id: "outage",
    label: "Streamlet engine outage",
    hint: "Logs it and pages the on-call team as critical.",
    task: () =>
      `ALERT TEST-INC-${stamp()}: the Streamlet engine went down at ${clock(true)}. ` +
      "Investigate: log it to the alerts notes and page the on-call team.",
  },
  {
    id: "summary",
    label: "Summarize today's alerts",
    hint: "Claude reads the alerts notes and sends the on-call team a short info page.",
    claudeOnly: true,
    task: () =>
      "Read the alerts notes and page the on-call team at info severity with a two-sentence summary of today's alerts.",
  },
];
