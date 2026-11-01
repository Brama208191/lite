# Keel Lite

A crash-safe runtime for LLM agents. An agent runs a multi-step task with tools (log a note, send an
email, charge a card). You can `kill -9` the worker in the middle of it, and another worker picks the
run up where it stopped **without sending the email or charging the card a second time**.

**Measured** ([full report](chaos-results/chaos-report.md), fake model, one laptop, 5 worker JVMs, lease TTL 2 s):

| | |
|---|---|
| `kill -9` trials on workers holding live runs | **300** (plus 30 `SIGSTOP` zombie trials and 19 injected halts) |
| Run interruptions | 1,284 across 1,904 runs; **1,904 / 1,904 completed** |
| Duplicate side effects, counted at the receiver | **0** (3,808 emails and charges, each applied once) |
| Missing side effects | 0 |
| Re-sends absorbed by idempotency keys | 443 |
| Zombie writes rejected by fencing tokens | 119 |
| Takeover after a kill, p50 / p95 / p99 | **2.04 s / 2.17 s / 2.54 s** |

Turn stable keys off and the same harness finds 28 duplicate charges and emails in only 30 kills
(see [Ablation](#ablation)).

## Why this is hard

An agent step is "ask the model, then call a tool". A worker can die at any point in between:

- **After the model answered, before the answer was saved.** A naive retry asks the model again, gets a
  different answer, and the agent goes off in another direction (and pays for the tokens twice).
- **After the payment API charged the card, before the worker wrote that down.** A naive retry charges
  again. This is the window that causes real double charges, and no amount of "check before you act" in
  the worker closes it, because the worker cannot know whether its last request landed.
- **A worker that is only paused** (GC pause, `SIGSTOP`, a VM migration) wakes up after someone else has
  taken over and keeps writing as if it still owned the run.

## How it works

```
                  POST /api/runs, SSE timeline, kill button
  Browser ───────────────────────────────────────────────► API (Spring Boot, KEEL_ROLE=api)
                                                             │ append RUN_CREATED
                                                             ▼
               ┌──────────────────────── PostgreSQL ──────────────────────────┐
               │ events(run_id, seq, type, payload jsonb, lease_token)  ◄─ the source of truth
               │ leases(run_id, token, worker_id, expires_at)            ◄─ fencing tokens
               │ notes(...)                                              ◄─ transactional tool
               └───────────────▲──────────────────────────────────────────────┘
                               │ claim (FOR UPDATE SKIP LOCKED), append with token check
                     ┌─────────┴─────────┐
                     │  Worker × N        │  same jar, KEEL_ROLE=worker
                     │  state = fold(log) │
                     │  next = decide()   │──── Idempotency-Key: run:turn:tool ────► Receiver (separate service)
                     └─────────┬─────────┘                                         fake email + payment API,
                               │                                                   dedupes keys like Stripe,
                               ▼                                                   counts every delivery
                      Model adapter: scripted fake (tests, chaos) | Claude via the Anthropic Java SDK (demos)
```

**1. The event log is the only state.** Every step is an append to `events`, keyed by `(run_id, seq)`.
A worker never keeps state it cannot rebuild: `RunState.fold(events)` is a pure function, and
`Decider.decide(state)` returns the next action (call the model, run tool *k*, complete, fail). Both live
in `dev.keel.core` with no I/O, so a unit test can cut a finished run at every position and check that
the resumed run takes exactly the step the original took, with the same keys
([`ReplayTest`](runtime/src/test/java/dev/keel/core/ReplayTest.java)).

**2. Model answers are events.** `MODEL_RESPONDED` stores the whole reply, including Claude's thinking
blocks and their signatures, so a resumed run replays the conversation byte for byte instead of asking
the model again. The tool calls a resumed run makes are the ones the model already chose.

**3. Leases with fencing tokens.** A worker claims a run by bumping `leases.token`. Every append runs
`SELECT token ... FOR UPDATE` in the same transaction as the insert and refuses to write if the token
moved. A paused zombie that wakes up after a takeover gets `FencedException` on its next write and
stops. Lease expiry uses the database clock, so worker clock skew does not matter.

**4. Three kinds of tools, three guarantees.**

| Tool | Kind | Guarantee | How |
|---|---|---|---|
| `notes_append` | writes to our own Postgres | **exactly once** | the note insert and its `TOOL_COMPLETED` event commit in one transaction, behind the fencing check |
| `send_email`, `charge_payment` | external side effects | **effectively once** | `TOOL_STARTED` is written first with a stable key `run:turn:tool`; a re-send after a crash reuses it and the receiver answers from its ledger |
| `http_fetch`, `notes_read` | read-only | safe to repeat | the result is recorded, so a resumed run does not fetch again |

**5. Unknown outcomes are retried, never reported as failures.** If the payment API times out, the
runtime does not tell the model "it failed" (the model would try again with a new call and a new key).
It keeps the run on the same tool call and re-sends with the same key, up to 5 attempts.

### The honest version of "exactly once"

Nothing can make an external API call exactly-once by itself. What Keel Lite guarantees is that the
**effect** happens once, provided the receiver honours idempotency keys (as Stripe, Adyen, SES with
`MessageDeduplicationId` and most payment APIs do). The chaos harness measures this at the receiver,
not by trusting the runtime's own logs. Effects on our own database are exactly-once without any help.

## Chaos harness

[`chaos/`](chaos/src/main/java/dev/keel/chaos) is a separate program that runs the real worker jar as
child JVMs and abuses them:

- **SIGKILL trials.** Picks a worker that holds live leases, sends a real `kill -9`, records which runs
  it held and what was last written for each, and starts a replacement JVM.
- **Zombie trials.** `SIGSTOP`s a worker past the lease TTL so another worker takes over, then
  `SIGCONT`s it. Every write it attempts afterwards must be rejected by fencing.
- **Injected halts.** With `KEEL_HALT_AFTER_EFFECT_RATE`, a worker calls `Runtime.halt` right after the
  receiver applied an effect and before the result is recorded: the worst possible moment.
- **Lost responses.** The receiver fails 2% of requests *after* committing the effect, so the runtime
  sees an error for something that actually happened.

It keeps 12 runs in flight across 5 workers the whole time, then checks:

- duplicate and missing effects **at the receiver**, by business reference (order id), independent of keys
- duplicate or missing note lines
- event log integrity: no write with a stale token, no `seq` gaps, no model turn or tool result recorded twice
- every interrupted run was taken over, and how long that took

Chaos runs use **only the scripted fake model**: they are free, deterministic and reproducible
(`SEED=42`). The Claude adapter goes through the same `ModelClient` interface and the same stored-reply
replay path.

### Ablation

`KEEL_STABLE_KEYS=false` makes the runtime generate a fresh idempotency key per attempt, the bug most
retry code has. Same harness, 30 `kill -9` trials
([report](chaos-results/ablation-unstable-keys/chaos-report.md)):

| | stable keys (300 kills) | fresh key per attempt (30 kills) |
|---|---|---|
| Duplicate emails and charges | **0** | **28** (one order charged 3×) |
| Duplicate note lines (transactional tool) | 0 | 0 |

The notes tool stays clean in both, because its guarantee comes from the shared transaction, not from keys.

### Reading the takeover numbers

Takeover is bounded below by the lease TTL (2 s here): a dead worker stops renewing, and the lease must
expire before anyone may claim the run. Two things are reported separately instead of being folded
into the percentiles: takeovers that were cut short because the claiming worker was itself killed
before writing anything (47 in the official run), and paused runs that nobody claimed during the pause,
whose owner simply continued after `SIGCONT` (2). With too little spare capacity the tail grows: an
earlier run with a 2% halt rate and 4 runs per worker, where replacement JVMs were always booting,
measured p95 3.6 s.

Run it yourself:

```bash
docker compose up -d --build postgres receiver api
```

```bash
docker compose --profile chaos run --rm chaos
```

The default is a quick 50-trial run (about 1.5 minutes). The published numbers use
`TRIALS=300 PAUSE_TRIALS=30` (about 8 minutes). `WORKERS`, `CHAOS_LEASE_TTL`, `KEEL_STABLE_KEYS` and
`SEED` change the run too. The
report lands in `chaos-results/chaos-report.md` and `.json`, with one log file per worker JVM.

## Dashboard

One page (React + TypeScript): start a run, watch the event log arrive over Server-Sent Events (the API
`LISTEN`s on a Postgres trigger), and press **Kill the worker**. The worker halts on its next heartbeat
with `Runtime.halt(137)`, which skips every `finally` block and shutdown hook just like `kill -9`, and
leaves its lease behind. A few seconds later the timeline shows another worker taking over with a higher
token, and the receiver panel shows each email and charge applied once, with the re-send counted as a
replay. (The dashboard asks the worker to halt itself because the API cannot signal a process on another
machine; the chaos harness is where real `kill -9` signals are sent.)

```bash
docker compose up --build
```

Then open http://localhost:8080. The demo workers use a slower fake model (1.5 to 2.5 s per turn) so
there is time to press the button. Set `ANTHROPIC_API_KEY` in `.env` to add Claude as a model choice.

## Running the tests

Java and Maven are only needed inside Docker:

```bash
./scripts/mvn.sh verify
```

The Maven cache is kept in `.cache/m2` inside the project folder (ignored by git). That runs the unit tests and the Testcontainers integration tests in a Maven container: replay at every
crash point, lease fencing and `SKIP LOCKED` claiming under contention, crash-after-effect, a paused
zombie racing a new owner, unknown outcomes, the stable-key ablation, the receiver's ledger under 16
concurrent requests with one key, the Anthropic request/response mapping, and the HTTP + SSE API.

## Project layout

```
runtime/   Spring Boot app, one jar for both roles (KEEL_ROLE=api|worker|all)
  core/      Event, RunState.fold, Decider.decide: pure, no I/O
  store/     EventStore (fenced append), LeaseStore (claim/renew), RunStore, WorkerStore
  model/     ModelClient, FakeModel (scripted), AnthropicModel (Anthropic Java SDK)
  tools/     send_email, charge_payment, notes_append, notes_read, http_fetch
  worker/    Worker (claim loop, heartbeats, kill switch), RunDriver (fold → decide → act → append)
  api/       REST + SSE, LISTEN/NOTIFY fan-out
receiver/  the fake email and payment provider, with its own schema and an idempotency ledger
chaos/     the kill -9 harness and report
web/       the dashboard
deploy/    Dockerfiles and Railway configs
```

## Deploy (Railway)

Railway runs this as four services from one repo. No Redis, no Docker-in-Docker.

1. **New project → Deploy from GitHub repo**, then add **Postgres** (Database → PostgreSQL).
2. Create three services from the same repo and point each at its config file under
   **Settings → Config-as-code**:

   | Service | Config file | Public domain |
   |---|---|---|
   | `api` | `deploy/railway/api.json` | yes (this serves the dashboard) |
   | `worker` | `deploy/railway/worker.json` | no |
   | `receiver` | `deploy/railway/receiver.json` | no |

3. Set variables (Railway's `${{...}}` references fill in the Postgres connection):

   Shared by all three: `PGHOST=${{Postgres.PGHOST}}`, `PGPORT=${{Postgres.PGPORT}}`,
   `PGDATABASE=${{Postgres.PGDATABASE}}`, `PGUSER=${{Postgres.PGUSER}}`, `PGPASSWORD=${{Postgres.PGPASSWORD}}`

   | Service | Variables |
   |---|---|
   | `receiver` | `PORT=8090` |
   | `api` | `KEEL_ROLE=api`, `KEEL_RECEIVER_URL=http://${{receiver.RAILWAY_PRIVATE_DOMAIN}}:8090`, `KEEL_MAX_RUNS_PER_MINUTE=30`, optionally `ANTHROPIC_API_KEY` and `KEEL_MAX_ANTHROPIC_RUNS_PER_HOUR=20` |
   | `worker` | `KEEL_ROLE=worker`, `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, `KEEL_RECEIVER_URL=http://${{receiver.RAILWAY_PRIVATE_DOMAIN}}:8090`, `KEEL_FAKE_MIN_LATENCY=1500ms`, `KEEL_FAKE_MAX_LATENCY=2500ms`, optionally `ANTHROPIC_API_KEY` |

4. In the `worker` service settings, set **Replicas** to 2, so a killed worker's run is taken over by the
   other replica within the lease TTL (3 s) while Railway restarts the killed one (`restartPolicyType:
   ALWAYS`). One replica also works, but the takeover then waits for the restart.
5. Deploy. Flyway creates the tables on first start (the receiver uses its own `receiver` schema).

Cost notes: every service runs a small JVM (`-XX:TieredStopAtLevel=1`, Serial GC). The fake model is
free; Claude runs are capped per hour by `KEEL_MAX_ANTHROPIC_RUNS_PER_HOUR` because the demo is public.
The model defaults to `claude-opus-5-5` at `low` effort (`KEEL_ANTHROPIC_MODEL`, `KEEL_ANTHROPIC_EFFORT`).

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `KEEL_ROLE` | `all` | `api`, `worker` or `all` |
| `KEEL_LEASE_TTL` | `3s` | how long a silent worker keeps a run; bounds takeover time |
| `KEEL_WORKER_CONCURRENCY` | `8` | runs per worker (virtual threads) |
| `KEEL_STABLE_KEYS` | `true` | `false` reproduces the classic bug (fresh key per attempt), for the ablation |
| `KEEL_HALT_AFTER_EFFECT_RATE` | `0` | chaos only: halt right after an effect, before recording it |
| `KEEL_RECEIVER_URL` | `http://localhost:8090` | where effect tools send requests |
| `ANTHROPIC_API_KEY` | empty | enables the Claude adapter |
| `RECEIVER_FAIL_AFTER_COMMIT_RATE` | `0` | receiver: answer 503 after committing the effect |

## Limits

- Takeover time is bounded below by the lease TTL. Detecting death faster needs an external failure
  detector; a lease is the simple, correct baseline.
- A run's model must be deterministic *given its stored history*, which is true for any model once its
  replies are in the log. What is not covered is a model call in flight at the moment of a crash: that
  call is lost and made again (the report counts these), but its reply was never acted on.
- The receiver has to honour idempotency keys. If an external API does not, the best you can do is the
  write-ahead `TOOL_STARTED` record plus a reconciliation check before re-sending; Keel Lite does not
  implement reconciliation.
- One Postgres is the coordination point. That is a deliberate trade for simplicity at this scale.
