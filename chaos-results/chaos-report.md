# Keel Lite chaos report

**Result: clean.** Fake model only, no LLM calls. Session `y1u7qn`, seed `42`, generated 2026-11-01T16:41:37.398183455Z.

| Metric | Value |
|---|---|
| `kill -9` trials (SIGKILL on a worker holding live leases) | 300 |
| Zombie trials (SIGSTOP past the lease TTL, then SIGCONT) | 30 |
| Injected halts right after an effect, before it was recorded | 19 |
| Runs interrupted mid-flight | 1284 |
| Runs completed | 1904 / 1904 |
| **Duplicate side effects, counted at the receiver** | **0** |
| Missing side effects | 0 |
| Retries absorbed by idempotency keys | 443 |
| Takeover after interruption, p50 / p95 / p99 | 2.04 s / 2.17 s / 2.54 s (max 4.08 s) |
| First new event after interruption, p50 / p95 / p99 | 2.18 s / 4.30 s / 4.81 s (max 7.08 s) |
| Zombie writes rejected by fencing | 119 |

## Setup

5 worker JVMs x 8 concurrent runs, 12 runs kept in flight, lease TTL 2000 ms, heartbeat 500 ms, stable idempotency keys: true, halt-after-effect rate 0.005. Wall time 489 s.

Every run logs a note (transactional tool), sends an email and charges a card (external effects). The receiver is a separate service; it dedupes by `Idempotency-Key` like Stripe does and counts business-level duplicates by order reference, independent of keys.

## Side effects at the receiver

| | |
|---|---|
| Requests received | 4251 |
| Effects applied | 3808 (expected 3808) |
| Requests answered from the key ledger (replays) | 443 |
| Key reused with a different payload | 0 |
| Duplicate effects (same order, more than once) | 0 |
| Notes lines (transactional tool) / duplicates / missing | 1904 / 0 / 0 |

## Event log integrity

| | |
|---|---|
| Writes with a stale lease token | 0 |
| Runs with a gap in `seq` | 0 |
| Model turns recorded twice | 0 |
| Tool results recorded twice | 0 |
| Tool calls re-sent after a crash (same key) | 374 |
| Model calls made / responses recorded | 6558 / 5712 (846 lost to crashes; a resumed run reuses every recorded response) |

## Where the interruptions landed

Last event in the log when the worker died or froze:

| Last event | What was in flight | Runs |
|---|---|---|
| LEASE_ACQUIRED | rebuilding state, then a model call or a tool call | 535 |
| MODEL_RESPONDED | about to record a tool start | 19 |
| TOOL_COMPLETED | the next tool call or model call | 371 |
| TOOL_STARTED | a tool call: the effect may have happened but is not recorded | 359 |

## Takeover time by trial type

| | p50 / p95 / p99 |
|---|---|
| SIGKILL | 2.04 s / 2.17 s / 2.58 s (max 4.08 s) |
| SIGSTOP | 2.03 s / 2.18 s / 2.20 s (max 2.20 s) |

47 interruption(s) are left out of the takeover times because the worker that claimed the run was itself killed before it wrote anything; that run's next takeover is measured from the second interruption.

2 paused run(s) were not claimed by anyone during the pause, so their owner's lease was still current after SIGCONT and it carried on with the same token.

Takeover is bounded by the lease TTL: a dead worker stops renewing, its lease expires, and the next poll claims the run with a higher fencing token.
