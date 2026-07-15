# Agentic Decision Intelligence Platform — Design Spec

**Date:** 2026-07-14
**Owner:** Nimish Kasaudhan
**Status:** Draft for stakeholder review
**Prototype target:** 30-day working demo
**Companion repo:** `agentic-rca-sample/` (working proof of the RCA vertical)

---

## 1. Problem statement

Contact-center supervisors and managers spend a disproportionate share of their day interpreting dashboards to answer three recurring questions:

1. **"Why is this metric moving?"** — e.g. Average Handle Time (AHT) has jumped in the Banking queue and no one knows why.
2. **"Which of my agents is starting to slip?"** — coaching interventions arrive after the shift is already impacted.
3. **"Which queues are about to breach SLA?"** — reactive triage instead of proactive load-balancing.

Existing NICE CXone dashboards answer *"what is happening?"* but leave *"why?"* and *"what next?"* to humans. This design introduces an **agentic AI decision layer** that closes that gap without replacing the underlying data platform.

## 2. Goals & non-goals

### Goals (for the 30-day prototype)
- Supervisors can ask natural-language questions and receive **structured, evidence-backed, confidence-scored** answers.
- Agents receive **proactive, personalized coaching nudges** when their metrics slip — before a supervisor has to intervene.
- Supervisors see a **live risk panel** showing skills/queues at risk of missing SLA in the next 60 minutes, with recommended next-best steps and predicted impact.
- All three capabilities share a single **AI foundation service** (LLM gateway, tool registry, trace log).
- End-to-end demo path is scripted and reliably reproducible for stakeholder review.

### Explicit non-goals
- Multi-tenant isolation, per-BU RBAC — one tenant/BU for the prototype.
- Fine-tuning or self-hosted LLMs — Claude on Bedrock only.
- PII redaction beyond a basic prompt-scrub — hardening is post-prototype.
- Autonomous writeback (no auto-reroute, no auto-schedule, no auto-scoring). Every AI action is advisory.
- Slack/Email/SMS delivery — nudges appear in the agent app, alerts in the supervisor dashboard only.
- Historical model training pipeline — the forecast model is pre-trained offline; scoring only in the runtime.

## 3. High-level architecture

```
                    ┌──────────────────────────────────────────────────┐
                    │  CXone Dashboard Shell (Angular)                 │
                    │  ┌───────────────┐  ┌──────────────┐  ┌────────┐ │
                    │  │ Supervisor    │  │ Risk Panel   │  │ Agent  │ │
                    │  │ Chat Panel    │  │ (SLA risks)  │  │ Nudge  │ │
                    │  └───────┬───────┘  └──────┬───────┘  └───┬────┘ │
                    └──────────┼─────────────────┼──────────────┼──────┘
                               │ REST/WebSocket  │              │ WS
                    ┌──────────▼─────────────────▼──────────────▼──────┐
                    │  API Gateway (Spring Cloud Gateway)              │
                    └──────────┬───────────────────────────────────────┘
                               │
       ┌───────────────────────┼────────────────────────────────────┐
       │                       │                                    │
┌──────▼────────┐   ┌──────────▼──────────┐   ┌─────────────────────▼──────┐
│ ms-agentic-   │   │ ms-agentic-coach    │   │ ms-agentic-risk-monitor    │
│ rca (chat)    │   │ (agent coaching)    │   │ (queue/SLA forecast)       │
│               │   │                     │   │                            │
│ - Intent      │   │ - Metric stream     │   │ - Skill utilization stream │
│ - Planner     │   │   subscriber        │   │   subscriber               │
│ - Tool router │   │ - Anomaly detector  │   │ - Forecast (Erlang+ML)     │
│ - Answer      │   │ - Nudge composer    │   │ - NBS recommender          │
│   composer    │   │   (LLM)             │   │                            │
└──────┬────────┘   └──────────┬──────────┘   └────────────┬───────────────┘
       │                       │                           │
       └───────────────────────┴───────────────────────────┘
                               │
                    ┌──────────▼────────────┐
                    │  ms-agentic-core      │
                    │                       │
                    │ - LLM Gateway         │  → Bedrock (Claude)
                    │ - Tool Registry       │
                    │ - Prompt Store        │
                    │ - Trace / Eval Log    │
                    │ - Notification Bus    │  → WebSocket fan-out
                    │ - Data Access Layer   │  ┌─→ Valkey (real-time)
                    │                       │──┤
                    │                       │  └─→ OpenSearch (historical)
                    └───────────────────────┘
```

### Why four services, not one
- **Different scaling profiles.** Chat is bursty and stateful per session; coaching runs a high-volume steady stream; risk monitoring is periodic and CPU-heavy. A single deployable would over-provision or throttle at least one of them.
- **Different failure blast radii.** A model regression that hurts nudge quality must not degrade chat.
- **Independent iteration.** Prompt / tool changes in one vertical don't force redeploys of the others.
- Monolithic would be *acceptable* for the 30-day demo. Splitting up front is a small ops cost that pays back the moment you pilot with a real BU.

## 4. Subsystem 1 — Conversational Root-Cause Analysis (`ms-agentic-rca`)

### 4.1 User story
A supervisor opens the CXone dashboard, opens the chat panel, and types:
> "Why has the Banking queue AHT increased today?"

They receive within ~15 seconds:
- A one-sentence summary of what happened.
- 1-3 ranked probable causes, each with a **confidence score (0-100)**, **supporting evidence** (bullet list of numbers pulled from data), and **one concrete recommended action**.
- A trace they can expand to see exactly which data the AI examined.

### 4.2 End-to-end flow
1. `POST /rca/v1/ask` with `{sessionId, question, context: {userId, buId}}`.
2. **Guardrail pass** — prompt-injection filter + length cap on user input.
3. **Intent classification** — the LLM emits `{intent, metric?, scope?, timeRange?}`. Unclassifiable → clarification prompt streamed back.
4. **Agent loop** (see below) — LLM plans and executes tool calls against the tool catalog.
5. **Deterministic evidence scorer** grades each proposed cause against the actual data.
6. **Answer composer** — LLM produces final structured JSON.
7. Full trace persisted to OpenSearch `agentic-traces-*`.
8. Response streamed back over WebSocket; JSON summary returned on the sync REST call for non-streaming clients.

### 4.3 Agent loop (already implemented in the sample)
```
conversation = [user_message]
for iteration in 0..MAX_ITERATIONS:
    resp = bedrock.converse(system, conversation, tools)
    conversation += resp.message
    if resp.stop_reason == TOOL_USE:
        for tool_use in resp.tool_uses:
            result = registry.get(tool_use.name).invoke(tool_use.input)
            conversation += tool_result(tool_use.id, result)
    else:
        return resp.final_text  # structured JSON
raise IterationBudgetExceeded
```

### 4.4 Tool catalog (v1)
| Tool | Backing store | Purpose |
|---|---|---|
| `metric_snapshot(scope, metric)` | Valkey | Current live value + delta vs baseline |
| `metric_history(scope, metric, range, bucket)` | OpenSearch | Hourly / daily time series |
| `contact_volume_breakdown(scope, window)` | OpenSearch | Volume by channel/skill/hour |
| `staffing_snapshot(scope, window)` | Valkey | Scheduled vs. logged-in vs. on-call |
| `agent_performance_slice(scope, dimension)` | OpenSearch | Group AHT/ACW by agent/team/tenure |
| `event_correlation(scope, window)` | OpenSearch | Deploys, schedule changes, outages |

All tools are read-only. Adding a tool = one Java class + Spring bean registration.

### 4.5 Deterministic evidence scorer
Purpose: keep hallucination in check by grading LLM-proposed causes against the actual tool data.

For each proposed cause, compute three sub-scores:
- **Significance** — z-score of the sub-factor's move vs. its 30-day baseline (capped at 100).
- **Consistency** — does the sub-factor's direction actually explain the metric direction? Sign check with a domain lookup table (e.g. "more new hires → higher AHT" is +; "more experienced agents → higher AHT" is −).
- **Coverage** — what fraction of the metric delta does this factor mathematically account for? Computed from the numbers in the tool results.

Final `confidence = 0.4·significance + 0.2·consistency + 0.4·coverage`. If any sub-score is unavailable, that factor's confidence is capped at 60 and flagged in the trace.

### 4.6 REST contract
```
POST /rca/v1/ask
{ "sessionId":"...", "question":"...", "context":{"userId":"...","buId":"..."} }

Response (200)
{ "sessionId":"...", "answer": {
    "summary":"...",
    "causes":[
      { "title":"...", "confidence": 78,
        "evidence":["...","..."],
        "recommendedAction":"..." }
    ]},
  "traceId":"..."
}

Streaming variant (SSE):
event: thinking      data: {"text":"Comparing today vs 30-day baseline"}
event: tool_call     data: {"tool":"metric_history","args":{...}}
event: tool_result   data: {"tool":"metric_history","summary":"5 hourly buckets"}
event: final         data: {answer JSON as above}
```

### 4.7 Non-functional targets
- p50 first token < 2s, p95 first token < 5s.
- p95 final answer < 15s (multi-tool).
- Iteration budget hard-capped at 8; typical 3-5.
- Per-question token budget: 20k in + 4k out.

## 5. Subsystem 2 — Proactive Agent Coaching (`ms-agentic-coach`)

### 5.1 User story
An agent handling calls sees a soft overlay nudge appear in their agent app mid-shift:
> "Your AHT is trending 22% above your usual. Two of the last three contacts were billing disputes — try the 3-step de-escalation from module B4 (30-sec refresher [link]). You've handled 12 today, great pace."

They can **dismiss**, **mark helpful**, or **open the resource**. All three feed back into the effectiveness log.

### 5.2 End-to-end flow
1. `ms-agentic-coach` subscribes to Kafka topic `agent.metrics.v1` (one event per agent per metric interval).
2. **Baseline builder** — for each `(agent, metric)` pair, maintain in Valkey a 30-day rolling EWMA + hour-of-day seasonal factor. Recomputed nightly from OpenSearch.
3. **Anomaly detector** — flag when `abs(current - baseline) / baseline_stddev > k` for `T` consecutive intervals. Defaults: `k=2.0, T=3` intervals of 5 minutes.
4. **Cooldown & rate-limit gate** — max 3 nudges per agent per shift, min 15 minutes between nudges, suppressed during active call (talk state).
5. **Context assembler** — pulls agent tenure, last 3 contact types, current queue, last coaching module viewed.
6. **Nudge composer** (LLM) — prompt template + context → personalized nudge. Uses **Haiku** (cheaper, faster) since the task is tightly constrained.
7. **Tone critic pass** (LLM) — a cheap second call rejects nudges that fail the tone rubric (not accusatory, no cross-agent comparisons, action-oriented).
8. **Delivery** — publish to Kafka `agent.nudge.v1`; the notification bus fans out over WebSocket to the agent's connected client.
9. **Feedback capture** — dismiss/helpful/opened events go back to OpenSearch `nudge-feedback-*` for offline analysis.

### 5.3 Guardrails (hard constraints in the prompt template)
- Never mention or compare to another named agent.
- Never state the agent's rank or percentile.
- Always end with one concrete, immediate action.
- Never exceed 280 characters of body text.
- Never link outside the internal LMS domain.

### 5.4 Contracts
```
Kafka in:  agent.metrics.v1       {agentId, metric, value, ts, queueId}
Kafka out: agent.nudge.v1         {agentId, body, actionUrl, ttlSec, correlationId}
REST:      GET  /coach/v1/agents/{id}/nudges/recent
           POST /coach/v1/nudges/{id}/feedback   {kind: dismiss|helpful|opened}
WS:        /ws/agents/{agentId}/nudges
```

### 5.5 Non-functional targets
- Anomaly → nudge delivered: p95 < 30s.
- Nudge false-positive rate: < 15% (measured by dismiss-without-helpful proxy on the demo dataset).
- Nudges/agent/shift: max 3; observed target < 2.

## 6. Subsystem 3 — Operational Risk Monitoring (`ms-agentic-risk-monitor`)

### 6.1 User story
A supervisor opens the Risk Panel. They see a live table:

| Skill | Risk | Forecast SLA (next 60m) | Top recommendation |
|---|---|---|---|
| Banking | **At-risk** | 82% (SLA target 90%) | Route overflow to `General-Support` — predicted +5% SLA |
| Collections | Watch | 91% | — |
| Sales | Low | 96% | — |

Clicking Banking expands to show:
- The forecast curve with the SLA target line.
- 2-3 next-best-step recommendations, each with predicted impact (from the Erlang re-simulation).
- Accept / dismiss buttons — both logged, no side effect in the prototype.

### 6.2 End-to-end flow
1. Every 60 seconds, `ms-agentic-risk-monitor` runs a **scoring cycle** per active skill in the BU.
2. **Feature vector** built from Valkey (current arrival rate, staffing, AHT, queue depth, oldest-in-queue).
3. **Forecast model** — Erlang-C computes baseline SLA probability given features; a gradient-boosted regressor (trained offline on last 30 days of OpenSearch data) adds a correction term.
4. **Risk classification** — bucket into `low / watch / at-risk / breaching`. State transitions are **debounced** (must persist for 2 cycles to change bucket) to avoid flicker.
5. **Next-Best-Step (NBS) generation** — for `at-risk / breaching` skills only, LLM picks 2-3 actions from a bounded catalog. Each action's predicted impact is computed by **re-running Erlang-C with the proposed staffing / routing change applied**, not by the LLM guessing.
6. **Push update** — WebSocket message to `/ws/supervisors/{buId}/risks` with the current snapshot.
7. Supervisor accept/dismiss logged to `nbs-decisions-*` OpenSearch index for later offline evaluation.

### 6.3 Bounded action catalog (v1)
| Action | Parameter | Simulator adjustment |
|---|---|---|
| `overflow_to` | target skill | Redirect X% of arrivals for T minutes |
| `pull_idle_from` | source skill | +N agents temporarily |
| `extend_shift` | duration | +N agents for T minutes |
| `pause_outbound` | duration | Recover N seconds of AHT per agent |

The LLM cannot invent actions. It picks from this list and provides parameters within declared ranges.

### 6.4 Contracts
```
GET  /risk/v1/skills                   → [{skillId, riskBucket, forecastSlaPct, ...}]
GET  /risk/v1/skills/{id}/rationale    → {features, forecast curve, recommendations, evidence}
POST /risk/v1/skills/{id}/decision     → log {action, params, decision: accept|dismiss, userId}
WS   /ws/supervisors/{buId}/risks      → server-pushed snapshot updates
```

### 6.5 Non-functional targets
- Scoring cycle wall clock: p95 < 20s for a BU with ≤ 50 skills.
- Recommendation freshness: no risk update older than 90s in the UI.
- False-alert rate: < 20% (skill flagged `at-risk` that never actually breached in the following 60m).

## 7. Shared foundation — `ms-agentic-core`

Cross-cutting library + service that all three feature services depend on.

### 7.1 Components
| Component | Responsibility |
|---|---|
| **`LlmGateway`** | Only class that calls Bedrock. Handles retries, timeouts, per-request token accounting, safety filter, prompt versioning. All models routed through a single `converse(request)` method. |
| **`ToolRegistry`** | Spring-managed registry of `AgentTool` beans, scoped by domain (RCA, coach, risk). Planner can only see tools in its scope. |
| **`PromptStore`** | Filesystem-based versioned prompts. Each service pins `promptVersion` in its own config. |
| **`DataAccessLayer`** | Thin façades over Valkey (`ValkeyMetricsClient`) and OpenSearch (`OsHistoricalClient`). Feature services depend on the façade, not the store, so tests can stub either. |
| **`NotificationBus`** | STOMP-over-SockJS WebSocket fan-out. ACL by userId/buId. |
| **`TraceLog`** | Every LLM turn + tool call written to OpenSearch `agentic-traces-*`. Doubles as the eval dataset — traces can be replayed offline against prompt changes. |
| **`GuardrailFilter`** | Input scrub (prompt-injection heuristics, length cap, PII regex allowlist). Applied at every ingress. |

### 7.2 Configuration (per feature service)
```yaml
agentic:
  bedrock:
    region: us-west-2
    model-id: us.anthropic.claude-sonnet-4-5-20250929-v1:0
    max-tokens: 2048
    temperature: 0.2
    max-agent-iterations: 6
  prompts:
    version: v1
  tools:
    scope: rca                   # one of: rca, coach, risk
  trace:
    index: agentic-traces
    retention-days: 30
```

### 7.3 Model tier assignment (default)
| Service | Task | Model |
|---|---|---|
| ms-agentic-rca | Planner + composer | **Sonnet 4.6** (balanced reasoning) |
| ms-agentic-coach | Nudge composer + tone critic | **Haiku 4.5** (cheap, templated) |
| ms-agentic-risk-monitor | NBS recommender | **Sonnet 4.6** (bounded reasoning + parameter selection) |
Model IDs are overridable per prompt via `PromptStore` metadata; the gateway abstracts the model choice.

## 8. Data model (essentials)

### 8.1 Valkey keys (TTL 5-15 min for live snapshots)
```
metric:snapshot:{scope}:{metric}          → JSON snapshot
staffing:{scope}                          → JSON staffing state
skill:features:{skillId}                  → feature vector for forecaster
agent:baseline:{agentId}:{metric}         → EWMA state
```

### 8.2 OpenSearch indices
```
cxcv-events-*                             (existing) source of truth for contact events
agentic-traces-*                          NEW — LLM traces
nudge-history-*                           NEW — nudge issuance + feedback
nbs-decisions-*                           NEW — supervisor accept/dismiss log
```

## 9. Cross-cutting concerns

### 9.1 Security & auth
- All REST/WS traffic passes through Spring Cloud Gateway; user identity established via existing CXone SSO (JWT).
- Bedrock access via IAM role attached to the service (EKS IRSA or EC2 instance profile). No API keys in code or config.
- Trace log includes `userId` for auditability but never full raw user PII — controlled by GuardrailFilter allowlist.

### 9.2 Observability
- OpenTelemetry traces end-to-end. Each `converse` call spans model, iteration, tool calls.
- CloudWatch metrics: token spend/minute/service, tool invocations by name, agent-loop iteration histogram, WebSocket connection count.
- Trace log itself is the primary eval surface — a replay tool can re-run any archived question against a new prompt/model.

### 9.3 Cost controls
- Per-request token budget enforced in `LlmGateway`; exceeding budget returns a graceful `"I need to narrow the scope"` response.
- Iteration budget hard-capped in the agent loop.
- Model tier defaults documented in Section 7.3; deviation requires a code change (not runtime toggle) to keep unit economics predictable.

### 9.4 Safety
- Prompt-injection filter at ingress (heuristic list of instruction-override phrases; low false-positive; logged when triggered).
- System prompts are pinned per service version. User input is never treated as instructions.
- Tools are the only side-channel; all are read-only in the prototype.
- LLM output that fails JSON-schema validation is retried once with a "your last response failed schema; here is the error" turn, then errored out.

## 10. Testing & evaluation strategy

### 10.1 Unit
- Each `AgentTool` tested with mocked DAL — asserts input schema, argument parsing, output shape.
- `RcaAgent` / `CoachOrchestrator` / `RiskScorer` tested with a mocked `LlmGateway` that returns scripted tool_use → tool_result → final sequences.

### 10.2 Integration
- Real Bedrock, real Valkey (Testcontainers), real OpenSearch (Testcontainers). Slow suite — run in CI nightly.

### 10.3 Eval harness (this is the important one)
- **30 labeled RCA scenarios** (question, expected top cause). Each scenario is a fixture that seeds Valkey + OpenSearch and asserts the answer.
- **20 labeled coaching scenarios** (agent metric trace, expected nudge classification).
- **10 labeled risk scenarios** (skill state snapshot, expected risk bucket + action).
- Harness runs on every prompt/model change and against every merge into `main`.
- Success gate for the demo: **RCA top-cause accuracy ≥ 70%**.

## 11. Non-functional posture (roll-up)

| Concern | Prototype target |
|---|---|
| RCA chat p95 final answer | ≤ 15s |
| Coach nudge latency (anomaly → delivered) | p95 ≤ 30s |
| Risk cycle wall clock | p95 ≤ 20s |
| RCA top-cause accuracy | ≥ 70% on eval set |
| Nudge false-positive rate | ≤ 15% |
| Risk false-alert rate | ≤ 20% |
| Per-question token budget | 20k in / 4k out |
| Coaching cadence | ≤ 3 nudges / agent / shift |

## 12. Trade-offs I made (flag if you want to revisit)

- **Hybrid LLM planner + deterministic scorer.** More work than pure-LLM RCA, but hallucination-resistant and produces defensible confidence scores.
- **Four services, not one.** Small ops overhead now; big flexibility later.
- **Bedrock/Claude, no fine-tuning.** Prompt engineering + tool constraints + eval harness get us to demo quality; fine-tuning is post-prototype work.
- **Bounded action catalog for NBS.** Restricts LLM creativity, but every recommendation is executable and simulatable. Free-form recommendations were rejected because "predicted impact" is uncomputable for them.
- **No autonomous writeback.** Every AI action is advisory in the prototype. Autonomous action is a separate risk conversation with pilot stakeholders.

## 13. Out-of-scope items and post-prototype backlog

- Multi-tenant / cross-BU aggregation.
- Fine-tuning Claude on internal transcripts.
- Voice input for supervisor chat.
- Auto-execution of NBS actions (requires WFM writeback authorization framework).
- Long-form report generation ("Weekly Ops Digest").
- Agent-facing chat (currently agent side is receive-only nudges).
- Mobile client for supervisors.

## 14. Open questions for stakeholders

1. Which BU / queue names should the demo use? (Currently: "Banking".)
2. Which agent app is the nudge overlay embedded into (Softphone? MAX Agent? Agent Interaction UI)?
3. What LMS URL space should nudge action links point at?
4. What's the retention policy for the trace log? (Draft: 30 days.)
5. Is the existing CXone SSO JWT sufficient, or do we need a service-specific token?

---

## Appendix A — Companion sample app

A working proof of Section 4 (RCA) exists in this repository at the root: `agentic-rca-sample/`.

- Single Spring Boot service; Java 17; AWS SDK Bedrock 2.28.
- Implements the agent loop, three tools (`metric_snapshot`, `metric_history`, `agent_performance_slice`), and a minimal chat UI.
- Uses `MockDataStore` for Valkey/OpenSearch — swap for real clients as the first task in Week 1.
- Verified live against `us.anthropic.claude-sonnet-4-5-20250929-v1:0` in `us-west-2` on 2026-07-14.
- Response for the "Banking AHT" question returned 2 causes at 85 / 70 confidence in ~18s after 4 autonomous tool calls.
