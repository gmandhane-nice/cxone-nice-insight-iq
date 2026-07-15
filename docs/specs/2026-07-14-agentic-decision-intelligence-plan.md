# Agentic Decision Intelligence Platform — 30-Day Implementation Plan

**Companion to:** `2026-07-14-agentic-decision-intelligence-design.md`
**Start date:** Week 1, Day 1 (adjust to actual kickoff)
**Team assumption:** 1-2 backend engineers, 1 frontend engineer, part-time PM/UX. Adjust duration linearly.

## Legend
- **DoD** = Definition of Done — the concrete acceptance criteria for a task.
- **Depends** = prerequisite task IDs.
- **Type** = `backend` / `frontend` / `data` / `infra` / `eval` / `ops`.

## Milestones

| Milestone | Week | Ships |
|---|---|---|
| M1 — Foundation | End of W1 | `ms-agentic-core` skeleton + real Bedrock + wired Valkey/OpenSearch DAL, working from a scripted seeder |
| M2 — RCA vertical | End of W2 | Supervisor chat panel producing evidence-backed answers on the demo scenario, end-to-end |
| M3 — Coach + Risk verticals | End of W3 | Live agent nudges + live risk panel, parallel builds |
| M4 — Demo hardening | End of W4 | Eval-gated, load-smoked, recorded demo path |

---

## Week 1 — Foundation (`ms-agentic-core` + shared plumbing)

### T-1.1 · Repo & module layout — `infra`
Create `agentic-platform/` monorepo (or 4 sibling repos following house convention). Sub-modules: `core-lib`, `ms-agentic-core`, `ms-agentic-rca`, `ms-agentic-coach`, `ms-agentic-risk-monitor`, `frontend-widgets`.
- **DoD:** All modules build clean with `mvn -q verify` from root; skeleton Spring Boot apps boot on their assigned ports (8080/8081/8082/8083).
- **Depends:** —

### T-1.2 · Bedrock IAM service role — `infra`
Provision a dedicated IAM role (`agentic-core-bedrock`) with `bedrock:InvokeModel` and `bedrock:Converse` on the Claude inference profiles in `us-west-2`. Attach to the EKS service account (IRSA) or EC2 instance profile.
- **DoD:** From the platform's runtime environment, `aws bedrock-runtime converse --model-id us.anthropic.claude-sonnet-4-5-... --messages '[...]'` returns 200 without developer federation creds.
- **Depends:** —

### T-1.3 · `core-lib`: `LlmGateway` — `backend`
Port and productionize the `RcaAgent`'s Bedrock-calling code from `agentic-rca-sample` into a reusable `LlmGateway` class in `core-lib`. Add: retry-with-backoff (throttling), per-request token counters, structured logging, configurable timeout.
- **DoD:** Unit tests with `MockedBedrockClient` cover: happy path, throttled retry, iteration budget exhaustion, JSON-schema retry after malformed final response. Coverage ≥ 80%.
- **Depends:** T-1.1

### T-1.4 · `core-lib`: `ToolRegistry` + `AgentTool` interface — `backend`
Extract from the sample. Add: domain-scoping (`@ToolScope("rca")` etc.), duplicate-name detection, tool discovery via Spring `@Component`.
- **DoD:** A feature service can declare `tools.scope: rca` in config and receive only the RCA tools from Spring auto-config. Integration test verifies scope isolation.
- **Depends:** T-1.1

### T-1.5 · `core-lib`: `PromptStore` — `backend`
Filesystem-based loader for `prompts/<name>/<version>.txt` with version pinning per service. Support template variables (`{{scope}}`, `{{context}}`).
- **DoD:** Prompt lookup misses fail loudly at startup; version bumps are picked up on next startup; template rendering unit-tested with 10 fixtures.
- **Depends:** T-1.1

### T-1.6 · `core-lib`: Valkey DAL façade — `backend`
`ValkeyMetricsClient` with methods `snapshot(scope, metric)`, `getStaffing(scope)`, `getBaseline(agentId, metric)`, `setSnapshot(...)`. Uses Lettuce; JSON-serialized values; TTL awareness.
- **DoD:** Testcontainers-based integration test covers all methods against a real Valkey/Redis instance; latency assertions p95 < 20ms per call.
- **Depends:** T-1.1

### T-1.7 · `core-lib`: OpenSearch DAL façade — `backend`
`OsHistoricalClient` with methods `metricHistory(scope, metric, range, bucket)`, `agentSlice(scope, dimension)`, `contactVolume(scope, window)`, `writeTrace(entry)`. Uses the existing CXCV OpenSearch client style.
- **DoD:** Testcontainers OpenSearch node; each method has a test that seeds fixtures then reads them back. Trace-write path stress-tested to 100 writes/sec.
- **Depends:** T-1.1

### T-1.8 · `core-lib`: `TraceLog` — `backend`
Wraps `OsHistoricalClient.writeTrace` with buffered async writes; auto-generates traceId; correlates all LLM turns + tool calls under one traceId.
- **DoD:** In a mock RCA session, all 5+ events (question, tool_call ×N, final) land in the same OS document group; log flush is async and never blocks the caller.
- **Depends:** T-1.7

### T-1.9 · `core-lib`: `GuardrailFilter` — `backend`
Prompt-injection heuristics (blocklist), length cap (2000 chars), simple PII regex allowlist. Returns a `SafeInput` or throws `InputRejected`.
- **DoD:** 20-case unit test file covering: benign inputs (pass), obvious injection ("ignore previous instructions"), oversize inputs, control chars.
- **Depends:** T-1.1

### T-1.10 · `core-lib`: `NotificationBus` — `backend`
STOMP-over-SockJS WebSocket configuration; per-user topic ACL; publish helper.
- **DoD:** Angular sample client can subscribe to `/user/queue/notifications` and receive a message published from a test.
- **Depends:** T-1.1

### T-1.11 · `ms-agentic-core` service — `backend`
Thin service that exposes health, config introspection, and the WebSocket endpoint. All feature services depend on `core-lib` at compile time and communicate with `ms-agentic-core` only for shared runtime concerns (WS fan-out, trace correlation UI).
- **DoD:** Service boots, `/actuator/health` returns UP, `/actuator/info` shows model IDs and prompt versions, `/ws` accepts a STOMP connection.
- **Depends:** T-1.3 through T-1.10

### T-1.12 · Synthetic data seeder — `data`
CLI tool that seeds Valkey + OpenSearch with the "Banking AHT increase" demo scenario (and 3-4 variant scenarios). Idempotent; reruns wipe first.
- **DoD:** `./seeder --scenario banking-aht-spike` populates both stores; the RCA sample repointed at real stores produces the same answer it produced against `MockDataStore`.
- **Depends:** T-1.6, T-1.7

### T-1.13 · CI pipeline — `infra`
GitHub Actions (or Jenkins per house convention) for: build, unit tests, coverage report, dependency vulnerability scan. Nightly job: integration tests against Testcontainers.
- **DoD:** PR checks turn green on the empty modules; nightly job runs and reports.
- **Depends:** T-1.1

### T-1.14 · Angular widget-host shell — `frontend`
Skeleton Angular app that hosts the three widgets (chat panel, risk panel, nudge overlay) as feature modules. Auth wired to CXone SSO.
- **DoD:** Empty widgets render in the shell; user identity available via `AuthService`; WS client library configured pointing at `ms-agentic-core`.
- **Depends:** T-1.11

**Week 1 exit criteria:** foundation service is up, core-lib is unit-tested, Bedrock works from the runtime environment, seeded stores return real data, Angular shell renders with SSO.

---

## Week 2 — RCA vertical (`ms-agentic-rca` + supervisor chat panel)

### T-2.1 · `ms-agentic-rca` service scaffold — `backend`
Spring Boot service depending on `core-lib`; declares `tools.scope: rca`; wires `LlmGateway`, `ToolRegistry`, `TraceLog`.
- **DoD:** Service boots; `/actuator/health` UP; tool registry logs 6 registered tools at startup.
- **Depends:** T-1.11

### T-2.2 · Port sample tools to real DAL — `backend`
Rewrite `MetricSnapshotTool`, `MetricHistoryTool`, `AgentPerformanceTool` to call `ValkeyMetricsClient` / `OsHistoricalClient` instead of `MockDataStore`. Remove `MockDataStore`.
- **DoD:** Each tool has an integration test using Testcontainers + the seeder; live-invocation returns the same shape the sample returned.
- **Depends:** T-2.1, T-1.6, T-1.7, T-1.12

### T-2.3 · Add missing tools — `backend`
Implement `contact_volume_breakdown`, `staffing_snapshot`, `event_correlation`. Same pattern as T-2.2.
- **DoD:** All 6 tools registered; each has ≥ 2 unit tests and 1 integration test.
- **Depends:** T-2.2

### T-2.4 · Intent classifier — `backend`
Pre-step that classifies user question into `{intent, metric, scope, timeRange}`. Falls back to a clarifying-question response if confidence < threshold.
- **DoD:** 20-case fixture file (Q → expected intent) — 90% classification accuracy.
- **Depends:** T-2.1

### T-2.5 · Agent loop hardening — `backend`
Port the `RcaAgent` loop into a `RcaOrchestrator`. Add: schema-validation retry, timeout per iteration, cancellation on client disconnect, streaming event emission.
- **DoD:** Under simulated malformed model output, orchestrator retries once with an error prompt then errors out. WS client sees `thinking` / `tool_call` / `final` events in order.
- **Depends:** T-2.1, T-1.3

### T-2.6 · Deterministic evidence scorer — `backend`
Implement the significance / consistency / coverage scorer per Section 4.5 of the design. Applied between raw LLM causes and final response.
- **DoD:** For the Banking scenario, the top cause scores ≥ 75 combined; for a fabricated cause with no supporting numbers, score is ≤ 40. Unit tests cover each sub-score in isolation.
- **Depends:** T-2.5

### T-2.7 · REST + SSE endpoints — `backend`
Implement `POST /rca/v1/ask` (sync JSON) and `POST /rca/v1/ask/stream` (SSE). Both go through `GuardrailFilter`.
- **DoD:** Curl smoke test returns 200 on both; malformed input returns 400 with a helpful body; injection attempts logged and rejected.
- **Depends:** T-2.5, T-1.9

### T-2.8 · Supervisor chat panel — `frontend`
Angular feature module: chat input, streaming message rendering, cause cards, expandable trace panel, error handling.
- **DoD:** Manual test — a supervisor asks the demo question, sees streaming events, sees the cards populate in real time, can expand the trace.
- **Depends:** T-1.14, T-2.7

### T-2.9 · RCA prompt tuning — `backend`
Iterate on `prompts/rca-planner/v1.txt` and `prompts/rca-composer/v1.txt` against the seeded scenarios. Pin final versions.
- **DoD:** Demo scenario ("Banking AHT") returns top cause = "new-hire cohort" with confidence ≥ 75 in 3 consecutive runs.
- **Depends:** T-2.6, T-1.12

### T-2.10 · End-to-end demo script — `ops`
Written runbook: which URL, which question to ask, expected timing, expected answer, fallback if a component is down.
- **DoD:** Someone who was not on the build team can execute the runbook and get the expected result.
- **Depends:** T-2.8, T-2.9

**Week 2 exit criteria:** the demo question works end-to-end from browser through streaming to a rendered answer card, with confidence ≥ 75 and reproducible timing.

---

## Week 3 — Coach + Risk verticals (parallel)

### Coach track

#### T-3.1 · `ms-agentic-coach` scaffold — `backend`
Service boot; `tools.scope: coach`; Kafka consumer bean for `agent.metrics.v1`; Kafka producer for `agent.nudge.v1`.
- **DoD:** Service boots; consumer connects; producer publishes a test message end-to-end.
- **Depends:** T-1.11

#### T-3.2 · Baseline builder — `backend`
Per-agent EWMA + hour-of-day seasonal factor. Recomputed nightly from OpenSearch history; live state in Valkey.
- **DoD:** Fixture: 30 days of synthetic data → baseline computed; hour-of-day factor differs from flat mean by > 5% in expected direction; unit tests for edge cases (no history, sparse history).
- **Depends:** T-3.1, T-1.6, T-1.7

#### T-3.3 · Anomaly detector — `backend`
Sliding window state machine: k·σ threshold, T consecutive intervals, cooldown timer per agent.
- **DoD:** 20-case fixture (metric trace → expected {fire | no-fire | cooldown}). Accuracy 100% on fixtures.
- **Depends:** T-3.2

#### T-3.4 · Context assembler — `backend`
Pulls agent tenure, last 3 contact types, current queue, last LMS module viewed. Cached in Valkey with 60s TTL.
- **DoD:** Given a firing anomaly for an agent in the seeded scenario, returns a fully-populated `NudgeContext` object; unit tests for each field.
- **Depends:** T-3.3

#### T-3.5 · Nudge composer (LLM) + tone critic — `backend`
Two prompts under `prompts/coach-nudge/v1.txt` and `prompts/coach-tone-critic/v1.txt`. Composer uses Haiku 4.5; tone critic uses Haiku.
- **DoD:** For each of 10 seeded firing scenarios, composed nudge passes the tone critic on ≥ 9. Manual review confirms no accusatory language, no cross-agent comparisons.
- **Depends:** T-3.4, T-1.3

#### T-3.6 · Nudge delivery over WS — `backend`
Producer publishes to `agent.nudge.v1`; consumer in `ms-agentic-core` fans out via `NotificationBus` to `/ws/agents/{agentId}/nudges`.
- **DoD:** End-to-end test: injected anomaly → nudge appears on a subscribed WS client within 30s p95.
- **Depends:** T-3.5, T-1.10

#### T-3.7 · Agent nudge overlay — `frontend`
Small Angular component that renders the nudge as a soft, dismissible overlay. Dismiss / helpful / open buttons feed `POST /coach/v1/nudges/{id}/feedback`.
- **DoD:** Manual test — nudge appears, all three buttons work and log correctly.
- **Depends:** T-1.14, T-3.6

#### T-3.8 · Feedback capture endpoint — `backend`
`POST /coach/v1/nudges/{id}/feedback` writes to `nudge-history-*` in OpenSearch.
- **DoD:** Round-trip test writes three feedback events; OS query returns them with correct correlationId.
- **Depends:** T-3.6, T-1.7

### Risk track

#### T-3.9 · `ms-agentic-risk-monitor` scaffold — `backend`
Service boot; `tools.scope: risk`; scheduled scoring cycle every 60s; Kafka consumer for skill state updates.
- **DoD:** Service boots; scheduled task fires; log line every cycle showing skills scored.
- **Depends:** T-1.11

#### T-3.10 · Feature vector builder — `backend`
Per skill: pulls current arrival rate, staffing, AHT, queue depth, oldest-in-queue from Valkey. Falls back to OpenSearch for cold reads.
- **DoD:** Unit test verifies feature schema; integration test against seeded Valkey; latency p95 < 50ms per skill.
- **Depends:** T-3.9, T-1.6, T-1.7

#### T-3.11 · Forecast model — `backend`
Erlang-C baseline + GBM correction. Model trained offline; `.onnx` or `.pmml` artifact loaded at startup.
- **DoD:** Model file checked in under `models/risk-v1.onnx`; startup log confirms load; test suite validates predictions against 10 labeled fixtures within ±5%.
- **Depends:** T-3.10

#### T-3.12 · Erlang-C simulator — `backend`
Standalone class `ErlangSimulator.simulate(features, action)` that returns predicted SLA under a proposed change. Used both by the risk classifier (baseline) and by the NBS impact estimator (with action).
- **DoD:** Unit tests against textbook Erlang-C values (5 fixtures). Deterministic under identical inputs.
- **Depends:** —

#### T-3.13 · Risk classifier + debouncer — `backend`
Bucket → `low / watch / at-risk / breaching`; state transitions require 2 consecutive cycles.
- **DoD:** State-machine unit tests cover: rising through all buckets, falling, oscillation suppressed. Fixture test on 5 skill traces.
- **Depends:** T-3.11

#### T-3.14 · NBS recommender — `backend`
For `at-risk / breaching` skills, LLM picks 2-3 actions from the bounded catalog. Impact computed via `ErlangSimulator.simulate` — LLM does not guess.
- **DoD:** For the seeded scenario, at least one recommendation with predicted impact ≥ +3% SLA. Fixture test.
- **Depends:** T-3.12, T-3.13, T-1.3

#### T-3.15 · REST + WS endpoints — `backend`
`GET /risk/v1/skills`, `GET /risk/v1/skills/{id}/rationale`, `POST /risk/v1/skills/{id}/decision`, `WS /ws/supervisors/{buId}/risks`.
- **DoD:** Contract tests pass; WS pushes updates within 90s of a scoring cycle change.
- **Depends:** T-3.14

#### T-3.16 · Risk panel UI — `frontend`
Live-updating table; expandable rationale card with forecast curve chart and recommendation cards with accept/dismiss.
- **DoD:** Manual demo: seed a breaching skill; supervisor sees it move from `watch` → `at-risk` in real time; expands to see actions; accepts one and confirms the decision is logged.
- **Depends:** T-1.14, T-3.15

**Week 3 exit criteria:** both verticals deliver end-to-end in demo scenarios: agent receives a nudge; supervisor sees a risk change and accepts a recommendation.

---

## Week 4 — Integration, eval, hardening, demo

### T-4.1 · Eval harness — `eval`
Runner that executes the 30 RCA + 20 coach + 10 risk fixtures against the deployed services and produces a pass/fail report.
- **DoD:** `./eval run` produces a report; failing scenarios include diff between expected and actual.
- **Depends:** T-2.9, T-3.5, T-3.14

### T-4.2 · Prompt / model tuning against eval — `backend/eval`
Iterate prompts and (where useful) model tier until the demo-gate NFRs are met.
- **DoD:** RCA top-cause accuracy ≥ 70%; nudge false-positive ≤ 15%; risk false-alert ≤ 20% on the eval set.
- **Depends:** T-4.1

### T-4.3 · Load smoke test — `ops`
Gatling scenario: 20 concurrent RCA sessions × 5 questions each; 500 agents streaming metrics; 50 skills scored every 60s. Duration 15 minutes.
- **DoD:** No 5xx; p95 targets from Section 11 met; no restart or leak; token spend per hour under agreed budget.
- **Depends:** T-4.1

### T-4.4 · Trace-replay tool — `eval`
CLI to replay any archived trace against the current prompt/model to check for regressions.
- **DoD:** Given a traceId, tool prints the delta between original and re-run responses side-by-side.
- **Depends:** T-1.8

### T-4.5 · Observability dashboards — `ops`
CloudWatch dashboard with per-service token spend, tool invocations, agent-loop iterations histogram, error rate, WS connection count.
- **DoD:** Single URL a demo host can share; every panel populated during the load smoke test.
- **Depends:** T-4.3

### T-4.6 · Demo runbook + recording — `ops`
Written runbook covering all three capabilities in one 12-minute demo; screen recording rehearsed 2×.
- **DoD:** Runbook survives a dry-run with an outside reviewer; recording published to a shared drive.
- **Depends:** T-4.2, T-3.7, T-3.16, T-2.10

### T-4.7 · Stakeholder review — `ops`
Present to identified stakeholders (see Section 14 open questions in the spec); collect written feedback; log follow-ups.
- **DoD:** Meeting held; feedback captured in a ticket per open question; go/no-go decision on pilot recorded.
- **Depends:** T-4.6

**Week 4 exit criteria:** demo is repeatable, evaluated, load-tested, observable, recorded, and reviewed with stakeholders.

---

## Cross-cutting continuous tasks (all weeks)

| Task | Cadence | Owner |
|---|---|---|
| Merge PR reviews within 24h | daily | eng lead |
| Trace log health check | daily | on-call |
| Bedrock cost check-in | 2×/week | eng lead |
| Prompt version bumps require eval pass | per PR | eng lead |
| Weekly demo to internal stakeholders | Friday | PM |

---

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Bedrock throttling under load | Med | High | Provisioned throughput or cross-region profile; retry-with-backoff already in LlmGateway. |
| Prompt regressions during tuning | High | Med | Every prompt change gated by eval harness (T-4.1). |
| OpenSearch cost / retention pressure | Med | Med | Trace retention capped at 30 days; nudge/nbs indices auto-rolled monthly. |
| Erlang-C model doesn't match reality closely enough | Med | Med | GBM correction term. If still off, calibrate against last 60 days at demo close. |
| Frontend widget-in-CXone integration surprises | Med | Med | Angular shell in T-1.14 mimics the CXone host contract; do a 1-day compatibility spike in W1. |
| Scope creep from stakeholders wanting autonomous action | Med | High | Design Section 12 documents "no writeback"; reiterate in every demo. |

---

## Post-prototype backlog (not in the 30 days)
See Section 13 of the design.
