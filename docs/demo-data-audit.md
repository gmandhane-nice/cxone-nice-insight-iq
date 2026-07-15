# Demo Data Audit — Sparkathon MVP

**Date:** 2026-07-15  
**Environment:** Stub data (all stubs live in `WidgetPayloadResolver.java`)

## Status Key
- 🟢 GREEN — data confirmed present / stub implemented
- 🟡 YELLOW — needs seeding or configuration
- 🔴 RED — blocked

---

## Scenario 1 — RCA: "Why has Banking AHT increased today?"

| Tool | Widget | Status | Store | Notes |
|---|---|---|---|---|
| `metric_snapshot` | `aht_summary` | 🟢 GREEN | Stub | Returns 412s current vs 305s baseline, +35.1% |
| `metric_history` | `metric_history` | 🟢 GREEN | Stub | Shows spike at 11:00 |
| `agent_performance_slice` | `agent_leaderboard` | 🟢 GREEN | Stub | 0-30 day cohort at 578s AHT |
| `staffing_snapshot` | `realtime_staffing` | 🟢 GREEN | Stub | 14 new hires added at 11:00 |
| `contact_volume_breakdown` | `contact_volume` | 🟢 GREEN | Stub | Volume spike at 11:00 |
| `queue_state` | `queue_state` | 🟢 GREEN | Stub | Status at-risk, SLA 82% |

**Expected result:** Top cause = "New-hire cohort (0-30 day tenure)", confidence ≥ 75

**To wire real data:**  
🟡 OpenSearch index: `aht_history` — confirm index exists with today's Banking data  
🟡 Snowflake table: `agent_metrics` — confirm hourly AHT by tenure bracket exists  
🟡 Snowflake table: `ops_events` — confirm agent batch-add event logged at 11:00  

---

## Scenario 2 — Risk: Banking skill at-risk

| Data | Status | Source | Notes |
|---|---|---|---|
| Risk snapshot JSON | 🟢 GREEN | `src/main/resources/fixtures/risk-snapshot.json` | Static file loaded at startup |
| Live queue_state decoration | 🟢 GREEN | `queue_state` widget stub | Returns depth=23, SLA=0.82 |

**Expected result:** Risk panel shows Banking as `at-risk` (red badge), SLA 79%, recommends 20% overflow to General-Support

---

## Scenario 3 — Coaching: Agent nudge

| Data | Status | Source | Notes |
|---|---|---|---|
| Coach trigger fixture | 🟢 GREEN | `src/main/resources/fixtures/coach-trigger-fixture.json` | POST body for /coach/trigger |
| Claude coaching prompt | 🟢 GREEN | `prompts/coach-nudge-v1.txt` | Created by Agent A |
| SSE stream endpoint | 🟢 GREEN | `/coach/nudges/stream` | Created by Agent E |

**Expected result:** Nudge card appears within 5s: "Your AHT is trending 22% above your usual. Two of the last three contacts were billing disputes — try the 3-step de-escalation from module B4. [action:B4]"

---

## Pending (need team confirmation)

1. **OpenSearch endpoint** — confirm host + port accessible on demo machine via VPN  
2. **Snowflake credentials** — confirm `SNOWFLAKE_URL`, `SNOWFLAKE_USER`, `SNOWFLAKE_PASSWORD` env vars available  
3. **Widget library Maven coords** — `com.nice.saas.wfo:?:?` for widget payload library  
4. **OpenSearch client library Maven coords** — for wiring real OpenSearch queries  
5. **Bedrock model access** — confirm `us.anthropic.claude-sonnet-4-5-20250929-v1:0` accessible in us-west-2  
