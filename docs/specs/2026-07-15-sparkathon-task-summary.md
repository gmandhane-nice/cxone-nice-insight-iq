# Sparkathon MVP — Detailed Task Summary

**Companion to:** `2026-07-15-sparkathon-mvp.md`
**Format:** One row per task with owner, effort, dependencies, concrete deliverable, and pass/fail Definition of Done.
**Assumed schedule:** Day 1 = 09:00–18:00 (with 1h lunch), Day 2 = 09:00–17:00.

## Legend

- **ID** — unique task id (matches design doc where applicable).
- **Owner** — team member (A/B/C/D/E as defined in the MVP design Section 7).
- **Slot** — clock hours reserved (Day-Hour, e.g. D1-10:00–13:00).
- **Effort** — expected wall-clock hours.
- **Depends** — prerequisite task IDs.
- **Deliverable** — the concrete artifact.
- **DoD** — the objective, verifiable pass/fail condition.
- **What it means** — plain-English summary of what's actually being done.

---

## Day 0 — Pre-sparkathon prep (30–60 min, ideally the evening before)

| ID | Owner | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **P-01** | A | 20m | Sample repo cloned + `mvn spring-boot:run` succeeds, `curl /rca/v1/ask` returns 200 on each teammate's laptop | Every teammate reports READY in the team chat | Make sure every teammate can download and run the starter app on their own laptop before the hackathon starts |
| **P-02** | A | 15m | Bedrock credentials refreshed (`aws sts get-caller-identity` returns the federation role in us-west-2) | Bedrock `converse` call returns 200 with "OK" from every teammate's laptop | Refresh the login keys so everyone can talk to Amazon's AI service (Bedrock) without hitting an "access denied" error |
| **P-03** | B | 30m | Location of existing widget-payload library, OpenSearch client library, Snowflake connector — Confluence links pasted in team chat | Team chat has all three links pinned | Find the three internal code libraries we'll reuse (data widget, search, database) and drop the links in the group chat so nobody wastes time hunting |
| **P-04** | C | 20m | Environment/host where OpenSearch + Snowflake creds are wired identified | The chosen environment reachable via VPN by every teammate; connection verified | Identify which server/environment already has the database passwords configured, and confirm everyone can reach it over VPN |
| **P-05** | E | 15m | Shared Google Drive / SharePoint folder created for deck, recording, screenshots | Link shared in team chat | Create a shared folder where we'll store the slide deck, demo recording, and screenshots — and share the link with everyone |
| **P-06** | D | 15m | Angular CLI installed locally; `ng --version` clean; Node 18+ | `ng new sparkathon-check` scaffolds without error | Make sure the web app development tool (Angular) is installed and working on the frontend dev's laptop |
| **P-07** | All | 5m | Everyone opens the design doc + task summary, skims Section 6 (scenarios) and Section 7 (splits) | Reactions in chat confirming ownership | Everyone reads the plan so they know what they're building and who owns what before Day 1 kicks off |

---

## Day 1 — Build (09:00 → 18:00 = 8h effective)

### 09:00 — 09:30 — Kickoff (all)

**KO-01** (owner: E, effort: 30m, all attend, DoD: minutes written to shared doc)

*What it means: 30-minute team meeting to confirm everyone knows the plan, owns their tasks, and can access the systems before the real work begins.*

Agenda:
1. Review scenarios (5m).
2. Confirm owners against the split (5m).
3. Confirm the Day-1 5 PM gate criteria (Section 9 of the design) (5m).
4. Slack channel norms — status every 2h, blockers immediately (5m).
5. Environment access sanity check — everyone hits `agentic-mvp/` dev host once (10m).

---

### 09:30 — 13:00 — Morning block

#### Person A — Backend / Agent core

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **A-1.1** | 09:30–10:00 | 30m | New module `agentic-mvp` under a fresh git repo, forked from `agentic-rca-sample`. Package renamed to `com.nice.agentic.mvp`. | `git log` shows fork commit; `mvn -q package` green | Copy the starter app into a brand-new project repo with a new name — our actual hackathon codebase starts here |
| **A-1.2** | 10:00–10:30 | 30m | `MockDataStore` deleted; all 3 sample tools compile against an empty `WidgetPayloadResolver` interface stub | Build breaks only in the tool `invoke()` methods, nowhere else | Delete the fake hard-coded test data from the sample app and put a blank placeholder where real data will go — clears the path for wiring in live data |
| **A-2.1** | 10:30–12:00 | 1h30m | `@ToolScope("rca"\|"coach"\|"risk")` annotation + `ToolRegistry` filters beans by active scope config. Each of the 6 RCA tool classes carries the annotation. | Startup log lists exactly 6 registered tools; changing scope to `coach` and rebooting drops them to 0 | Build a labeling system so each AI tool is tagged with its use case (RCA, coaching, or risk). The app will only load the tools that match the active mode |
| **A-2.2** | 12:00–13:00 | 1h | Each RCA tool's `invoke()` calls `widgetResolver.resolve(WIDGET_ID, args)` and JSON-serializes the DTO | Unit test with a mocked resolver — happy path returns valid JSON per tool | Wire each of the 6 AI tools so they actually fetch data from the resolver and return a clean, structured response instead of doing nothing |

#### Person B — Data access & widget bridge

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **B-1.1** | 09:30–10:30 | 1h | One-page cheat-sheet in `docs/widget-library-cheatsheet.md` covering: entry point class, payload builder, execute method, return types, error modes, config needed | Two teammates (A, D) can read it and answer "how do I fetch the AHT summary for Banking?" without asking questions | Write a one-page "how to use this library" guide so other teammates don't have to dig through the source code to figure out how to fetch data |
| **B-2.1** | 10:30–12:00 | 1h30m | `WidgetPayloadResolver` bean with a `Map<String, WidgetSpec>` table of the 6 widgets we need. `resolve(widgetId, args)` returns typed DTO. | Unit test with a mocked widget-library client calls `AHT_SUMMARY` and gets back a `MetricSnapshotDto` | Build the central "data lookup" component that knows which of the 6 data widgets to call and returns the result in a clean, usable format |
| **B-2.2** | 12:00–13:00 | 1h | Wire the real widget-library client into the resolver (auto-config or manual bean). Live test: `resolve("aht_summary", {scope:"Banking",timeRange:"today"})` returns real data. | Curl through the resolver via a temporary debug endpoint prints real numbers from the environment | Connect the resolver to the actual internal data library so it pulls live numbers from the real system instead of returning dummy values |

#### Person C — Data & scenarios

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **C-1.1** | 09:30–11:00 | 1h30m | Data audit: for each of the 3 scenarios, document (in `docs/demo-data-audit.md`) whether the data exists, in which store, in which index/table, for which time range | Doc committed; each scenario marked GREEN (exists) / YELLOW (seedable) / RED (blocked) | Check whether the data our 3 demo stories need actually exists in the databases. Mark each as green (ready), yellow (needs seeding), or red (blocked) |
| **C-1.2** | 11:00–13:00 | 2h | For any YELLOW: write and run a seeder script (Snowflake INSERTs or OpenSearch bulk) that lands the Banking-AHT-spike pattern in the right timeframe | Ad-hoc query against the store returns the expected shape (35% spike at hour 11, new-hire cohort concentrated) | For any data that's missing, write a script to insert realistic fake data into the database so the demo story plays out correctly |

#### Person D — Frontend

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **D-1.1** | 09:30–11:00 | 1h30m | Angular workspace `frontend/` created; three feature modules `chat/`, `risk/`, `agent-nudge/`. Routes wired. Shared `theme.scss`. | `ng serve` → open the three routes, each shows a placeholder | Scaffold the web app with 3 pages (chat, risk dashboard, agent nudge). Each page shows a blank placeholder — the structure is ready for content |
| **D-1.2** | 11:00–13:00 | 2h | Chat panel skeleton: input, send button, message list. No streaming yet — just a POST to `/rca/v1/ask` and render the JSON dump. | Manual test: ask a question, see the JSON response dumped below | Build a basic chat box — just an input field, a send button, and the raw AI response shown below. No fancy formatting yet, just proof it works end-to-end |

#### Person E — Coach + Risk backends + demo lead

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **E-1.1** | 09:30–11:00 | 1h30m | `prompts/coach-nudge-v1.txt` + `prompts/coach-tone-critic-v1.txt` drafted with the guardrails from Section 5.3 of the main design | Two peer reviewers (A, C) sign off in the shared doc | Write the instruction text that tells Claude how to compose a short coaching message for a contact-centre agent — tone, length, what to include |
| **E-1.2** | 11:00–13:00 | 2h | `POST /coach/trigger` accepts `{agentId, metric, deltaPct}`, builds a context fixture, calls Claude (Haiku 4.5) with the nudge prompt, returns the composed nudge JSON | `curl -X POST /coach/trigger -d '{"agentId":"AG-042","metric":"AHT","deltaPct":22}'` returns a nudge under 280 chars, action link included | Build the API endpoint that takes "agent X's AHT went up 22%" and returns a short, friendly coaching message generated by Claude |

---

### 13:00 — 14:00 — Lunch (mandatory, no working through)

---

### 14:00 — 18:00 — Afternoon block

#### Person A

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **A-3.1** | 14:00–16:00 | 2h | `QueryGenerator` service: single Claude call with a JSON-schema-constrained tool that outputs `{store, index_or_table, filters[], aggs[], timeRange, limit}` | Unit test: given "any deploys in the last 3h on Banking?" the generator emits a descriptor targeting `ops_events` in Snowflake with a bounded time range | Build the service that turns a plain English question ("any deploys in the last 3 hours?") into a structured database query the system can actually run |
| **A-4.1** | 16:00–17:30 | 1h30m | `QueryValidator` — allowlist enforcement + timeRange cap (≤ 24h) + limit cap (≤ 500) + reject any DDL/DML keyword | 5 malicious inputs unit test: DROP, UNION, out-of-allowlist index, unbounded time, oversize limit — all rejected with distinct error codes | Add a safety filter that blocks any query that could damage the database (e.g. DELETE, DROP), asks for too much data, or touches tables it shouldn't |
| **A-4.2** | 17:30–18:00 | 30m | Wire generator + validator into a `AdHocQueryTool` (7th tool, scope=rca) that Claude can call when no widget covers the ask | Manual test: ask "any deploys today in Banking?" — Claude picks `ad_hoc_query`, generator emits, validator passes, executor is stubbed | Combine the query generator and validator into a single AI tool so Claude can use it as a "last resort" when none of the 6 standard widgets cover the question |

#### Person B

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **B-3.1** | 14:00–16:00 | 2h | All 6 tools return real data via the widget resolver for the Banking scenario | 6 unit tests + one integration test that runs the full 6-tool sequence returning real numbers | Verify that all 6 data tools are now returning genuine live numbers for the Banking demo scenario — not stubs, not placeholders |
| **B-4.1** | 16:00–18:00 | 2h | `QueryExecutor`: takes a validated descriptor, dispatches to either the existing OpenSearch client (via a translator that converts descriptor → DSL) or the existing Snowflake client (descriptor → SQL). Results mapped to `TabularResult` DTO. | For the descriptor produced by A-3.1, executor returns real rows from Snowflake `ops_events` | Build the component that actually runs the validated query — it converts the structured descriptor into the right query language (OpenSearch or SQL) and fires it at the real database |

#### Person C

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **C-1.3** | 14:00–16:00 | 2h | End-to-end verify Scenario 1 data: the 6 real tools each return the numbers we expect. Corrections applied to seeder as needed. | Excel/paste table: for each tool, expected vs. actual values matches within tolerance | Run all 6 tools against the seeded demo data and confirm the numbers match expectations. Fix the seeder if anything's off |
| **C-2.1** | 16:00–18:00 | 2h | Scenario 2 static risk snapshot committed as `resources/fixtures/risk-snapshot.json` — 3 skills, Banking as `at-risk` with `overflow_to: General-Support`, predicted +5% SLA | Loaded by `RiskSnapshot` bean at startup; `GET /risk/snapshot` returns the JSON verbatim | Create the JSON file that represents our "staffing risk" demo story — Banking skill is at risk, overflow goes to General Support, SLA prediction included |

#### Person D

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **D-2.1** | 14:00–16:00 | 2h | Chat panel renders **cause cards** properly (from JSON response, no streaming yet): title, confidence badge, evidence bullets, recommended action | Manual test: ask the Banking question against the running backend, see 1-3 cards populate | Replace the raw JSON dump with nicely formatted "cause cards" — each card shows a root cause title, a confidence percentage, supporting evidence, and a recommended action |
| **D-3.1** | 16:00–17:00 | 1h | Risk panel: table with columns `Skill \| Risk \| Forecast SLA \| Top Recommendation`. Fed from `GET /risk/snapshot`, polls every 10s. | Panel loads, shows 3 rows, Banking row is `at-risk` red-badged | Build the risk dashboard panel — a table showing which skills are at risk, their SLA forecast, and a recommendation. Refreshes automatically every 10 seconds |
| **D-4.1** | 17:00–18:00 | 1h | Agent nudge overlay component: card at bottom-right of the agent route, appears when a message arrives on a subscribed SSE stream from `/coach/nudges/stream` | Component compiles, renders with a fixture payload — SSE subscription in place | Build the pop-up coaching card that appears in the bottom corner of the agent's screen when a new nudge message arrives from the server |

#### Person E

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **E-2.1** | 14:00–16:00 | 2h | `GET /risk/snapshot` — reads Person C's JSON, decorates with a live `queue_state` widget call for Banking, returns to client | Curl returns the full snapshot; Banking's `queue_depth` field is live (not from the file) | Build the risk snapshot API endpoint — it serves the static JSON file but replaces the queue depth field with a live number pulled from the real data widget |
| **E-3.1** | 16:00–17:00 | 1h | `/coach/nudges/stream` — SSE endpoint that emits a nudge whenever `POST /coach/trigger` fires | Two-terminal test: one curl `/stream`, one POST `/trigger` — the stream terminal prints the nudge JSON | Build the real-time push endpoint: when a coaching nudge is triggered, it's instantly pushed to any browser that's listening — no page refresh needed |
| **E-3.2** | 17:00–18:00 | 1h | Demo runbook v0 — the sequence of clicks and commands for the 3 scenarios, timings, expected outputs | Committed to `docs/demo-runbook.md` | Write the step-by-step demo script: exact clicks, exact commands, what the judges should see at each step, and how long each part takes |

---

### 17:00 — 18:00 — Day-1 5 PM readiness gate (all)

**GATE-01** (owner: E, effort: 15m, all attend)

*What it means: A quick team check-in to decide whether we're on track or need to cut scope for Day 2.*

Run through Section 9 of the MVP design in the team chat. Each item explicitly ✅ or ❌ by the responsible owner:

- [ ] Service boots; Bedrock call works from the shared dev environment (**A**)
- [ ] `WidgetPayloadResolver` returns real data for at least `metric_snapshot` and `metric_history` (**B**)
- [ ] Angular shell renders; chat input reaches the backend (**D**)
- [ ] Demo Scenario 1 data is confirmed present (**C**)
- [ ] `POST /coach/trigger` produces a valid nudge JSON (may not render yet) (**E**)

**If 2 or more ❌:** cut Scenario 3 (live coaching) to a static screenshot in the deck. Reallocate that person's Day-2 effort to whichever scenario is weakest.

---

## Day 2 — Integrate, tune, demo (09:00 → 17:00 = 7h effective)

### 09:00 — 09:15 — Standup (all)

**KO-02** (owner: E, effort: 15m)

*What it means: Quick 15-minute morning check-in — surface any overnight blockers and confirm the Day 2 plan.*

- Blockers from Day 1?
- Gate results — anything cut?
- Confirm the noon checklist owners (Section 10 of the design).

---

### 09:15 — 12:00 — Morning block

#### Person A

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **A-5.1** | 09:15–12:00 | 2h45m | SSE endpoint `POST /rca/v1/ask/stream` — emits `thinking`, `tool_call`, `tool_result`, `final` events. Reuses the existing agent loop; hooks emit into it. | Curl `-N` shows events in order for a Banking question; final event carries valid JSON | Add real-time streaming to the RCA endpoint so the browser receives a live play-by-play ("thinking… calling metric_snapshot… got result… finalising…") instead of waiting for one big response |

#### Person B

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **B-5.1** | 09:15–11:00 | 1h45m | End-to-end ad-hoc scenario: "any deploys in the last 3 hours on Banking?" runs through generator → validator → executor → Claude → answer | Manual test: exact question typed in chat panel, ad-hoc tool invoked, deploy events surfaced in the answer | Test the full free-form query path: type a question that no widget covers, watch it go through generation → safety check → execution → Claude answer, all the way to the chat panel |
| **B-5.2** | 11:00–12:00 | 1h | 3 edge-case tests: (a) descriptor requesting non-allowlisted table, (b) unbounded time range, (c) empty result set. Each has a clean, non-crashing user-visible response. | Manual tests all pass, no 5xx, chat panel renders a helpful message | Make sure the app handles bad or edge-case queries gracefully — shows a helpful message instead of crashing or throwing a raw error |

#### Person C

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **C-3.1** | 09:15–11:00 | 1h45m | Scenario 3 coaching fixture — the exact `NudgeContext` JSON that Person E's endpoint accepts, wired into the demo runbook | `curl /coach/trigger` with this fixture produces the exact nudge text we'll demo | Prepare the exact input payload that will trigger the coaching demo — so during the presentation we can fire one command and get the exact nudge we rehearsed |
| **C-4.1** | 11:00–12:00 | 1h | Run all 3 scenarios end-to-end 3 times each; log any flakiness in a shared spreadsheet | Spreadsheet shows 9 rows; ≥ 8 green | Do a full dress rehearsal of all 3 demo stories 3 times each and log the results. If 8 out of 9 runs pass, we're good |

#### Person D

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **D-5.1** | 09:15–11:00 | 1h45m | Chat panel consumes the SSE stream — renders `thinking` and `tool_call` events as a live investigation log above the final cards | Visual: user asks → sees "Investigating…" then bullet lines "→ metric_snapshot", "→ metric_history" then final cards | Connect the chat panel to the real-time stream so users see a live "investigation log" — each data call appears line by line before the final answer cards load |
| **D-5.2** | 11:00–12:00 | 1h | Nudge overlay wired to the real `/coach/nudges/stream` SSE. Dismiss button removes it. | Trigger → overlay appears within 2s → click dismiss → gone | Connect the nudge pop-up to the real server stream and add a dismiss button so it actually disappears when clicked |

#### Person E

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **E-4.1** | 09:15–11:00 | 1h45m | Demo runbook v1 — final version with exact URLs, exact commands, expected timing, fallback if a step fails, list of "known safe" questions | Runbook is executable by any teammate without asking questions | Finalise the demo script so thoroughly that any teammate can pick it up and run the demo without asking a single question — includes fallback steps if something breaks |
| **E-4.2** | 11:00–12:00 | 1h | Deck outline (10 slides): 1 title, 1 problem, 1 solution, 1 architecture, 4 demo screenshots, 1 results/metrics, 1 ask/next steps | Deck skeleton in shared drive; content bullets under each slide | Build the 10-slide deck skeleton — title, problem, solution, architecture diagram, 4 demo screenshot placeholders, results, and next steps |

---

### 12:00 — 12:30 — Day-2 noon checklist (all)

**GATE-02** (owner: E, effort: 30m)

*What it means: Go/no-go decision point before the final polish stretch — if too many things are broken, switch to the backup recording.*

- [ ] Scenario 1 runs end-to-end in the browser, ≥ 3 consecutive successful trials (**A + D**)
- [ ] Scenario 2 loads with correct risk row (**E + D**)
- [ ] Scenario 3 fires from the trigger endpoint (**E + D**)
- [ ] Backup: pre-recorded video ready to play if live demo fails (**E**)

**If 2 or more ❌:** cut live demo, present recording only, adjust deck.

---

### 12:30 — 13:30 — Lunch

---

### 13:30 — 17:00 — Polish & Demo (3h30m)

#### Person A

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **A-6.1** | 13:30–15:30 | 2h | Prompt tuning against Scenario 1: iterate `rca-planner-v1.txt` + `rca-composer-v1.txt` until top cause = "new-hire cohort" with confidence ≥ 75 in 3 consecutive runs | 3-run log in the spreadsheet shows ≥ 75 confidence, correct top cause, in each run | Keep tweaking the AI prompt instructions until the system reliably identifies "new-hire cohort" as the top root cause with at least 75% confidence, 3 runs in a row |
| **A-6.2** | 15:30–17:00 | 1h30m | On-call support for whatever's broken; final code freeze at 16:30 | No commits after 16:30 except from this task; git tag `sparkathon-mvp` cut | Be the team's firefighter for last-minute issues. At 16:30 lock the code and tag the final version — no more changes after that |

#### Person B

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **B-6.1** | 13:30–15:30 | 2h | Support Person A on prompt tuning: adjust widget queries or tool descriptions if the agent picks the wrong tool | Trace log for each Scenario 1 run reviewed; tool-choice mistakes fixed by widget/tool description tweaks | Help with prompt tuning by fixing how tools describe themselves — if Claude keeps picking the wrong tool, tweak the tool's description until it makes the right choice |
| **B-6.2** | 15:30–17:00 | 1h30m | Backup: switch off ad-hoc query path via config if it becomes flaky (feature flag `agentic.mvp.enable-adhoc: false`) | Flag exists; demo runbook has the toggle instructions | Add an on/off switch for the ad-hoc query feature so we can disable it instantly during the demo if it starts misbehaving |

#### Person C

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **C-5.1** | 13:30–15:30 | 2h | Data warden: run each demo scenario every 30m from now until demo, log timing + result | Spreadsheet shows 4+ clean runs before 17:00 | Act as data watchdog — run all 3 scenarios every 30 minutes and log the results to catch any data drift or expiry before it causes a live demo failure |
| **C-5.2** | 15:30–17:00 | 1h30m | If any data drift detected (e.g. TTL expired snapshot in Valkey), re-seed | Final pre-demo run at 16:45 all green | If the warden runs detect stale or missing data (e.g. a cache expired), re-run the seed scripts to restore it. Final check at 16:45 must be all green |

#### Person D

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **D-6.1** | 13:30–15:00 | 1h30m | Visual polish: one theme, loading spinners, favicon, page title, error boundaries on all three panels | Manual walkthrough by Person E — no visual bugs called out | Make the UI look presentable: consistent colours/fonts across all 3 pages, loading spinners, a favicon, proper page titles, and graceful error messages if something breaks |
| **D-6.2** | 15:00–17:00 | 2h | Support demo rehearsals; hotfix any UI issues that come up | Two full runbook rehearsals with zero UI-side stops | Support the two full rehearsals and fix any UI glitches that show up — the goal is two clean run-throughs with no front-end hiccups |

#### Person E

| ID | Slot | Effort | Deliverable | DoD | What it means |
|---|---|---|---|---|---|
| **E-5.1** | 13:30–14:30 | 1h | First demo rehearsal with the team (dry run) | Runbook works end-to-end; timing recorded; issues logged | Run the first full team rehearsal, time it, and write down every issue that comes up |
| **E-5.2** | 14:30–15:30 | 1h | Fix pass on issues from E-5.1 | Owners of each issue confirm resolution | Go through every issue logged in the rehearsal, assign fixes to the right person, and confirm everything's resolved before the second rehearsal |
| **E-6.1** | 15:30–16:30 | 1h | Record the demo (screen + voice, 5 min) | Recording uploaded to shared drive; playable end-to-end | Record the 5-minute demo with screen capture and voiceover — this is our backup if the live demo fails, and evidence of what we built |
| **E-6.2** | 16:30–17:00 | 30m | Final deck pass — screenshots from the recording embedded | Deck exported to PDF, uploaded to shared drive | Grab screenshots from the recording, drop them into the slide deck, export to PDF, and upload — deck is now final |
| **E-7.1** | 17:00 | 15m | Final team check-in; retrospective notes | Retro doc committed; go-forward next steps captured | Quick end-of-hackathon team debrief — what went well, what didn't, and what we'd do differently. Committed to the repo as institutional memory |

---

## Task summary matrix (at-a-glance)

| Owner | Day-1 tasks | Day-2 tasks | Total effort |
|---|---|---|---|
| **A** — Backend / Agent | A-1.1, A-1.2, A-2.1, A-2.2, A-3.1, A-4.1, A-4.2 | A-5.1, A-6.1, A-6.2 | ~14.5h |
| **B** — Data access | B-1.1, B-2.1, B-2.2, B-3.1, B-4.1 | B-5.1, B-5.2, B-6.1, B-6.2 | ~14.5h |
| **C** — Data & scenarios | C-1.1, C-1.2, C-1.3, C-2.1 | C-3.1, C-4.1, C-5.1, C-5.2 | ~14h |
| **D** — Frontend | D-1.1, D-1.2, D-2.1, D-3.1, D-4.1 | D-5.1, D-5.2, D-6.1, D-6.2 | ~14.5h |
| **E** — Coach + Risk + Demo | E-1.1, E-1.2, E-2.1, E-3.1, E-3.2 | E-4.1, E-4.2, E-5.1, E-5.2, E-6.1, E-6.2, E-7.1 | ~14h |

Buffer: ~1.5-2h per person absorbed into support tasks (A-6.2, B-6.1, C-5.1, D-6.2, E-5.2).

---

## Critical-path timeline (must-hit checkpoints)

| Time | Checkpoint | Owner |
|---|---|---|
| **D1 09:30** | Kickoff done | E |
| **D1 12:00** | Sample forked, tools stubbed, widget resolver skeleton exists | A + B |
| **D1 14:00** | Widget resolver returns real Banking data | B |
| **D1 16:00** | All 6 tools returning real data end-to-end | B |
| **D1 17:00** | Day-1 gate passed | E (all) |
| **D2 12:00** | Day-2 noon checklist passed | E (all) |
| **D2 15:30** | Recording captured | E |
| **D2 16:30** | Code freeze | A |
| **D2 17:00** | Final deck + retro | E |

---

## Communication rules (short)

- **Status update every 2 hours** in the team chat: 1 line each — *"done X, on Y, blocked by Z."*
- **Blockers immediately** — don't wait for the 2h checkpoint if you're stuck.
- **No solo debugging past 30 min** — grab whoever wrote the code you're calling.
- **Code review** — quick PR review by 1 teammate before merge; no long feedback loops.
- **Push often** — nothing sits on a laptop for more than 2 hours.

---

## Fallback ladder (if things slip)

Cut in this order:

1. **First to cut:** live coaching stream → static screenshot + trigger endpoint returns JSON in Postman only.
2. **Second to cut:** SSE streaming on RCA → sync REST call, chat panel just renders the final JSON with a loading spinner (~15s wait).
3. **Third to cut:** ad-hoc query path (Path B) → six tools + widgets only, no free-form questions.
4. **Fourth to cut:** risk-panel live decoration → pure static JSON from the file.
5. **Nuclear option:** live demo → recorded video only; walk the judges through slides + video.

Never cut Scenario 1 (RCA). That's the show.

---

## Deliverables checklist (Day 2, 17:00)

- [ ] Running service + UI on the demo host
- [ ] `git tag sparkathon-mvp` cut
- [ ] Demo recording (5 min) uploaded
- [ ] Deck (10 slides) exported to PDF and uploaded
- [ ] Runbook committed to repo
- [ ] Retrospective notes committed
- [ ] Screenshot of a successful Scenario 1 run pinned in team chat
