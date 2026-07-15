# Sparkathon MVP — Agentic Decision Intelligence

**Timeline:** 2 days (16 working hours per person)
**Team:** 5 members
**Goal:** A live, demo-able prototype of the three capabilities riding on the **existing NICE data plumbing** — OpenSearch client library, Snowflake connector, pre-defined widget payloads.
**Non-goal:** Anything requiring new infrastructure, new pipelines, or work that outlives the sparkathon.

---

## 1. What "MVP" means for this sparkathon

Cut the design to a demo path that:

- Uses **only what already exists** — existing OpenSearch library, existing Snowflake connection code, existing widget payload library.
- **Reuses pre-defined widget payloads** when the tool matches a known widget; **generates a query** only when it doesn't.
- Runs end-to-end on one laptop or one shared dev box.
- Wraps up in a 5-minute recorded demo + a 10-slide deck.

Three capabilities, all sharing one Bedrock-hosted Claude + one agent loop:

| Capability | Demo flow | Fallback if time is tight |
|---|---|---|
| **Conversational RCA** | Supervisor asks "Why has Banking AHT increased today?" → structured answer | Must-ship |
| **Proactive Coaching** | Trigger an anomaly for one agent → nudge appears | Ship as scripted demo, not live stream |
| **Risk Monitoring** | Risk panel shows one skill flipping to `at-risk` with an NBS recommendation | Ship as pre-computed static snapshot if streaming isn't ready |

If we have to drop one, drop **live streaming** for Coaching and Risk — keep the *output surface* (a nudge card, a risk panel with one row) even if it's populated by a manual trigger.

---

## 2. Architecture (MVP shape)

```
                 ┌────────────────────────────┐
                 │ Single Angular page        │
                 │  ├─ Chat panel (RCA)       │
                 │  ├─ Risk panel (Risk)      │
                 │  └─ Agent overlay (Coach)  │
                 └────────────┬───────────────┘
                              │ REST + SSE
                 ┌────────────▼───────────────┐
                 │ ONE Spring Boot service    │
                 │  agentic-mvp                │
                 │                             │
                 │  ┌────────────────────────┐ │
                 │  │ Agent loop (existing)  │ │
                 │  └───────┬────────────────┘ │
                 │          │                  │
                 │  ┌───────▼────────────────┐ │
                 │  │ Tool router            │ │
                 │  └───────┬────────────────┘ │
                 │          │                  │
                 │  ┌───────▼──────────────┐  │
                 │  │ Widget payload lib   │──┼─► existing widget library
                 │  │  (predefined path)   │  │
                 │  └──────────────────────┘  │
                 │  ┌───────────────────────┐ │
                 │  │ Query generator (LLM) │─┼─► existing OpenSearch client
                 │  │  (ad-hoc path)        │─┼─► existing Snowflake client
                 │  └───────────────────────┘ │
                 │                             │
                 │  ┌───────────────────────┐ │
                 │  │ Bedrock client (Claude)│─┼─► AWS Bedrock (already verified)
                 │  └───────────────────────┘ │
                 └────────────────────────────┘
```

**One service. One frontend. Three panels.** All shared code lives in the same repo — no core-lib extraction, no cross-service Kafka.

### Why one service (not four)
- Two days.
- The design's four-service split is right for production; it's overhead we can't pay in a sparkathon.
- Everything ports out cleanly later — the code shapes (LLM gateway, tool registry, DAL façade) are identical whether they live in one JAR or four.

---

## 3. Data access — two paths, in order of preference

### Path A: Predefined widget payload (FAST PATH)
When a tool maps to a known widget:

```
Tool invoked (metric_snapshot Banking AHT)
    │
    ▼
WidgetPayloadResolver.resolve("aht-summary", {scope:"Banking", timeRange:"today"})
    │
    ▼
Existing widget library builds the payload
    │
    ▼
Existing library executes and returns typed data
```

- We don't touch the query, we don't touch the store.
- Zero risk of ad-hoc query bugs.
- Fast (existing library is already optimized).

**Coverage in the MVP:**
| Tool | Existing widget it maps to |
|---|---|
| `metric_snapshot` | AHT / ACW / Abandon summary widget |
| `metric_history` | Metric-over-time chart widget |
| `agent_performance_slice` | Agent leaderboard widget |
| `staffing_snapshot` | Realtime staffing widget |
| `contact_volume_breakdown` | Contact volume widget |
| `queue_state` | Queue state widget (for Risk panel) |

If a widget exists for it, we route through the widget library. Period.

### Path B: Ad-hoc query (SLOW PATH)
When Claude asks for something no widget covers:

```
Tool receives a natural-language sub-question
    │
    ▼
QueryGenerator (LLM call)  ──► produces a JSON query descriptor:
                                { store: "opensearch"|"snowflake",
                                  index / table,
                                  filters, aggs, buckets, timeRange, limit }
    │
    ▼
QueryValidator (deterministic)  ──► enforces:
                                    - store ∈ allowlist
                                    - index/table ∈ allowlist
                                    - no destructive ops
                                    - result size cap
                                    - timeRange bounded
    │
    ▼
Existing OpenSearch client  or  existing Snowflake client
    │
    ▼
Result mapped into the same DTO the widget path uses
```

**Query safety in the MVP:**
- The LLM produces a **JSON descriptor**, never raw SQL / DSL. The executor translates the descriptor into a query using the existing library. There is no path where Claude's text becomes an executable string directly.
- `QueryValidator` enforces an allowlist of tables/indices and rejects the descriptor otherwise. We ship with 3-4 allowlisted sources.
- Every ad-hoc query is logged in the trace with its descriptor for review.

We start with only **one** ad-hoc scenario for the demo (e.g. "any deploys in the last 3 hours on Banking?" → `ops_events` in Snowflake). Everything else uses widgets.

---

## 4. Components (single service)

| Component | Responsibility | Lines of code (rough) |
|---|---|---|
| `AgentLoop` | Bedrock converse loop from the sample — already working | ~120 (already exists) |
| `ToolRegistry` | Auto-discovers `AgentTool` beans | ~40 |
| `WidgetPayloadResolver` | Maps `(tool, args)` → widget id + payload; calls widget library | ~150 |
| `QueryGenerator` | LLM-backed JSON-descriptor generator (Path B) | ~100 |
| `QueryValidator` | Deterministic safety pass over the descriptor | ~80 |
| `QueryExecutor` | Executes descriptor via OpenSearch or Snowflake client | ~120 |
| Six `AgentTool` classes | Thin adapters: parse args → call resolver or executor → serialize | ~30 each |
| `RcaOrchestrator` | Runs agent loop, emits SSE events, returns final JSON | ~100 |
| `CoachTrigger` | Manual endpoint to fire a scripted anomaly for demo | ~60 |
| `RiskSnapshot` | Static/scheduled forecast producer for the risk panel | ~120 |
| REST controllers | 3 endpoints: `/rca/ask`, `/coach/trigger`, `/risk/snapshot` | ~50 |
| Angular UI (3 panels) | Chat, risk table, nudge overlay | ~600 across 3 modules |

**Total new code: ~1800 LOC.** Plausible for 5 people × 2 days.

---

## 5. What we're deliberately NOT building

| Skipped | Why |
|---|---|
| Separate `ms-agentic-core` service | One JAR fits in two days |
| Kafka streams for coach / risk | Poll or manual trigger instead |
| STOMP WebSocket / SockJS | SSE is enough for RCA streaming; the other two poll |
| Deterministic evidence scorer | Ship with LLM-provided confidence + a note; scorer is a fast-follow |
| Erlang-C simulator | Risk uses a simple thresholding rule on live queue depth + SLA |
| Eval harness | Manual scenario runs instead; document the 3 demo scenarios |
| Baseline builder / EWMA | Coaching anomaly is triggered manually or from a fixture |
| CI pipeline | Local `mvn verify` is enough |
| Auth / SSO | Single hardcoded user for the demo |
| Multi-tenant / RBAC | Out of scope |

---

## 6. Demo scenarios (script these first, everything backs into them)

### Scenario 1 — RCA (live)
- Supervisor asks: *"Why has the Banking queue AHT increased today?"*
- Claude calls: `metric_snapshot`, `metric_history`, `agent_performance_slice`.
- Seed data must show a **clear new-hire cohort spike** at a known hour.
- Expected result: top cause = new-hire cohort, confidence ≥ 75.

### Scenario 2 — Risk (live snapshot)
- Risk panel loads, shows Banking as `at-risk` (SLA forecast 82%).
- One recommendation: *"Overflow 20% to General-Support for 30 min — predicted +5% SLA."*
- Data is a static snapshot loaded at service startup; predicted-impact number is pre-computed.

### Scenario 3 — Coaching (triggered)
- Demo host presses `POST /coach/trigger`.
- Nudge appears in the agent overlay within ~5s:
  *"Your AHT is trending 22% above your usual. Two of the last three contacts were billing disputes — try the 3-step de-escalation from module B4."*

---

## 7. Team split (5 members, 2 days)

Names below are placeholders — swap with your team.

### Person A — Backend / Agent core (owner: the person who already knows the sample)
**Day 1**
- **A-1** Fork the existing sample into `agentic-mvp`. Rename package. Retire `MockDataStore` for real widget-backed data. *(1h)*
- **A-2** Add `ToolRegistry` scope annotations + wire the 6 tool classes to depend on `WidgetPayloadResolver` OR `QueryExecutor`. *(2h)*
- **A-3** Implement `QueryGenerator` — one Claude call, JSON schema constraining output to `{store, index, filters, aggs, timeRange, limit}`. *(3h)*
- **A-4** Implement `QueryValidator` — allowlist store/index, cap timeRange, cap limit. Unit tests for 5 malicious inputs. *(2h)*

**Day 2**
- **A-5** Streaming SSE endpoint `/rca/v1/ask/stream` — emit `thinking`, `tool_call`, `tool_result`, `final`. *(3h)*
- **A-6** Prompt tuning against Scenario 1 until confidence ≥ 75 in three consecutive runs. *(2h)*
- **A-7** Support Person E on demo dry-runs + code fixes. *(3h)*

**Owner of:** `AgentLoop`, `ToolRegistry`, `QueryGenerator`, `QueryValidator`, all prompt files.

### Person B — Data access & widget library bridge
**Day 1**
- **B-1** Identify the existing widget library entry-point + write a 1-page cheat-sheet for the team. *(1h)*
- **B-2** Implement `WidgetPayloadResolver` — a Spring bean that takes `(toolName, args)`, returns typed DTO. Table of widget-ids inside the class. *(3h)*
- **B-3** Wire the six tools to `WidgetPayloadResolver`. Each tool: parse args, call resolver, serialize DTO to JSON. *(3h)*

**Day 2**
- **B-4** Implement `QueryExecutor` — takes validated descriptor, dispatches to OpenSearch or Snowflake client, maps result into a generic `TabularResult` DTO. *(4h)*
- **B-5** One ad-hoc scenario end-to-end: "any deploys in the last 3 hours on Banking?" — descriptor → validator → Snowflake → result. *(2h)*
- **B-6** Support Person A on tool prompts + edge cases. *(2h)*

**Owner of:** `WidgetPayloadResolver`, `QueryExecutor`, all six tool adapters.

### Person C — Data seeding & scenarios
**Day 1**
- **C-1** Confirm the three demo scenarios' data exists (or seed it) in the OpenSearch / Snowflake environment the team will point at. Document what is where. *(3h)*
- **C-2** If seeding is needed: write a one-off script that populates the "Banking AHT spike" pattern in the correct time window. Idempotent. *(3h)*
- **C-3** Pre-compute the risk snapshot payload for Scenario 2 (JSON file the service loads at startup). *(2h)*

**Day 2**
- **C-4** Author the coaching-trigger fixture — the exact context payload Person D's overlay will render for Scenario 3. *(2h)*
- **C-5** Run all three scenarios end-to-end multiple times; verify data is stable across dry-runs. *(3h)*
- **C-6** Own the "known good" fixture set — the last checkpoint before demo. *(3h)*

**Owner of:** demo data quality, scenario fixtures, environment sanity.

### Person D — Frontend (Angular)
**Day 1**
- **D-1** Fork the sample's `index.html` into a proper Angular app in `frontend/`. Route `/`, `/risk`, `/agent`. Shared styling. *(3h)*
- **D-2** Chat panel — streaming SSE parser, cause cards, expandable trace, error handling. *(4h)*
- **D-3** Risk panel — table with skill / risk badge / forecast SLA / recommendation. Static-file load for Day 1. *(1h)*

**Day 2**
- **D-4** Agent nudge overlay — dismissible card component, appears on `POST /coach/trigger`. *(3h)*
- **D-5** Wire the risk panel to `GET /risk/snapshot` and poll every 10s. *(2h)*
- **D-6** Demo polish — one theme, one favicon, consistent typography, sensible loading states. *(3h)*

**Owner of:** the entire user-facing surface.

### Person E — Coach + Risk backends + demo lead
**Day 1**
- **E-1** Implement `POST /coach/trigger` — takes `{agentId}`, builds context, calls Claude (Haiku 4.5) with the coaching prompt, streams nudge JSON to the client via SSE. *(4h)*
- **E-2** Implement `GET /risk/snapshot` — reads Person C's precomputed JSON, decorates with a live `queue_state` widget call for one metric, returns to UI. *(3h)*
- **E-3** Author the coaching prompt + tone-critic prompt. *(1h)*

**Day 2**
- **E-4** Prepare the demo runbook (below). *(2h)*
- **E-5** Rehearse the demo twice with the team; fix any 5xx / timing surprises. *(3h)*
- **E-6** Record the demo (screen recording + voice-over). *(2h)*
- **E-7** 10-slide deck: problem, solution, architecture, demo, results, ask. *(1h)*

**Owner of:** coaching + risk endpoints, demo runbook, recording, deck.

---

## 8. Dependency graph (who's blocked on whom)

```
A-1 ─┬─► A-2 ─► A-3 ─► A-4 ─► A-5 ─► A-6
     │
B-1 ─┴─► B-2 ─► B-3
              │
              └─► B-4 ─► B-5

C-1 ─► C-2 ─► C-5
      C-3 ─────► C-4

D-1 ─► D-2 ─► D-5 ─► D-6
D-1 ─► D-3
D-1 ─► D-4

E-1 ─► E-2 ─► E-4 ─► E-5 ─► E-6 ─► E-7
```

**Critical path:** A-1 → A-2 → B-3 → D-2 → A-5 → E-5. Anyone falling behind here delays the demo.

**Parallelism opportunity end of Day 1:** A/B/D can all iterate independently once A-2 lands. C works in parallel from hour 1.

---

## 9. Day-1 end-of-day gate (do this at 5 PM)

Before anyone goes home Day 1, all these must be true or we replan:

- [ ] Service boots; Bedrock call works from the shared dev environment.
- [ ] `WidgetPayloadResolver` returns real data for at least `metric_snapshot` and `metric_history`.
- [ ] Angular shell renders; chat input reaches the backend.
- [ ] Demo Scenario 1 data is confirmed present.
- [ ] `POST /coach/trigger` produces a valid nudge JSON (may not render yet).

If two or more are missing, cut Scenario 3 (coaching) to a static screenshot in the deck.

---

## 10. Day-2 demo checklist (do this at noon)

- [ ] Scenario 1 runs end-to-end in the browser, ≥ 3 consecutive successful trials.
- [ ] Scenario 2 loads with correct risk row.
- [ ] Scenario 3 fires from the trigger endpoint.
- [ ] Recording is captured.
- [ ] Deck is finalized.
- [ ] Backup plan: pre-recorded video ready to play if live demo fails.

---

## 11. Risks & mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Widget library API surprises | High | Person B spends the first hour on the cheat-sheet, blocks everyone else early if there's a hitch |
| Bedrock throttling | Med | Fall back to Haiku 4.5 (already verified in your account); reduce iteration budget |
| OpenSearch/Snowflake creds not available on demo machine | High | Person C confirms end-to-end connectivity in the first 2 hours |
| Streaming SSE flaky | Med | Fall back to sync REST (no `/stream` endpoint); Person D handles both |
| Prompt regressions during tuning | Med | Person A pins prompt versions; final version tagged in git |
| Live demo network fails | Med | Person E's recording is the safety net |

---

## 12. Deliverables at end of Day 2

1. **Running service + UI** in the shared dev environment.
2. **Recorded demo** (5 min) — hosted somewhere the judges can play it.
3. **Deck** (10 slides).
4. **This design doc + a 1-page architecture PDF** for judges who want to dig in.
5. **Repo tag** `sparkathon-mvp` marking the final state.

---

## Appendix — Reused vs. new code

| Reused (existing) | New (this sparkathon) |
|---|---|
| OpenSearch client library | Agent loop (from the sample repo) |
| Snowflake connection code | Six tool adapters |
| Widget payload library | `WidgetPayloadResolver` |
| CXone SSO (if we bother — otherwise skip) | `QueryGenerator` + `QueryValidator` |
| Bedrock account + IAM role (already verified) | `QueryExecutor` (wraps existing clients) |
| The sample repo's Bedrock + agent loop code | Angular 3-panel UI |
| | Coaching / risk endpoints |
| | Prompts (RCA planner, RCA composer, coach nudge, coach tone critic) |

---

## Appendix — Prompts to author (owner: Person A + E)

1. `rca-planner-v1.txt` — decides which tool to call next given the conversation.
2. `rca-composer-v1.txt` — produces the final JSON answer.
3. `query-generator-v1.txt` — produces a JSON query descriptor for Path B.
4. `coach-nudge-v1.txt` — produces a personalized nudge.
5. `coach-tone-critic-v1.txt` — accepts or rejects a nudge on tone grounds.

All under `src/main/resources/prompts/`.
