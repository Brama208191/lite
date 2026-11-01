type Input = Record<string, any>;

export function money(cents: number, currency: string) {
  const amount = (Number(cents) / 100).toFixed(2);
  return currency?.toLowerCase() === "usd" ? `$${amount}` : `${amount} ${String(currency ?? "").toUpperCase()}`;
}

function host(url: string) {
  try {
    const parsed = new URL(url);
    return parsed.host + (parsed.pathname === "/" ? "" : parsed.pathname);
  } catch {
    return url;
  }
}

export function describeCall(name: string, input: Input): string {
  switch (name) {
    case "send_email":
      return `Email ${input.to} about “${input.subject}”`;
    case "charge_payment":
      return `Charge ${money(input.amount_cents, input.currency)} to ${input.customer} for ${input.reference}`;
    case "notes_append":
      return `Add “${input.line}” to the ${input.key} notes`;
    case "notes_read":
      return `Read the ${input.key} notes`;
    case "http_fetch":
      return `Fetch ${host(input.url)}`;
    case "page_oncall":
      return `Page the on-call team (${input.severity}): “${input.summary}”`;
    case "place_trade":
      return `${input.side === "sell" ? "Sell" : "Buy"} ${money(input.notional_cents, "usd")} of ${input.symbol} (paper trade) for ${input.reference}`;
    default:
      return `Use ${name.replace(/_/g, " ")}`;
  }
}

export function describeStart(name: string): string {
  switch (name) {
    case "send_email":
      return "Sending the email";
    case "charge_payment":
      return "Charging the card";
    case "notes_append":
      return "Writing the note";
    case "notes_read":
      return "Reading notes";
    case "http_fetch":
      return "Fetching the page";
    case "place_trade":
      return "Placing the trade";
    case "page_oncall":
      return "Paging the on-call team";
    default:
      return `Running ${name.replace(/_/g, " ")}`;
  }
}

export function describeResult(name: string | undefined, content: string, isError: boolean): string {
  if (isError) {
    return `Failed: ${content.replace(/^Receiver rejected the request \(\d+\): /, "")}`;
  }
  let parsed: Input | null = null;
  try {
    parsed = JSON.parse(content);
  } catch {
    parsed = null;
  }
  if (parsed && typeof parsed === "object") {
    if (name === "charge_payment" || parsed.id?.startsWith?.("ch_")) {
      return `Card charged ${money(parsed.amount_cents, parsed.currency)}, receipt ${parsed.id}`;
    }
    if (name === "page_oncall" || parsed.id?.startsWith?.("in_")) {
      return `Incident ${parsed.id} opened (${parsed.severity}), paged ${parsed.paged}`;
    }
    if (name === "place_trade" || parsed.id?.startsWith?.("tr_")) {
      return `Paper trade filled: ${parsed.side} ${money(parsed.notional_cents, "usd")} of ${parsed.symbol}, ticket ${parsed.id}`;
    }
    if (name === "send_email" || parsed.id?.startsWith?.("em_")) {
      return `Email delivered, message ${parsed.id}`;
    }
  }
  return content.length > 220 ? `${content.slice(0, 220)}…` : content;
}
