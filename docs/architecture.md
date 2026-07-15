# Agentic MVP — Architecture Overview

## Service layout (single Spring Boot JAR)

```
POST /rca/v1/ask          → RcaController → RcaAgent (sync)
POST /rca/v1/ask/stream   → RcaStreamController → RcaAgent (SSE)
POST /coach/trigger        → CoachController → Claude Haiku 4.5 → SSE broadcast
GET  /risk/snapshot        → RiskController → static JSON + live queue_state widget
GET  /coach/nudges/stream  → CoachController → SSE stream (persistent)
```

## Tool registry (scope=rca, 7 tools)
1. `metric_snapshot` → aht_summary widget
2. `metric_history` → metric_history widget
3. `agent_performance_slice` → agent_leaderboard widget
4. `staffing_snapshot` → realtime_staffing widget
5. `contact_volume_breakdown` → contact_volume widget
6. `queue_state` → queue_state widget
7. `ad_hoc_query` → QueryGenerator → QueryValidator → QueryExecutor

## Data flow (Scenario 1)
```
User question
    │
    ▼
RcaAgent (Bedrock converse loop, max 6 iterations)
    │
    ├─► metric_snapshot("Banking", "AHT") → WidgetPayloadResolver → aht_summary stub/widget
    ├─► metric_history("Banking", "AHT", "today") → metric_history stub/widget
    └─► agent_performance_slice("Banking", "tenure") → agent_leaderboard stub/widget
    │
    ▼
Final JSON: {summary, causes[{title, confidence, evidence[], recommendedAction}]}
```

## Stub vs real wiring status
| Component | Status | What's needed to go live |
|---|---|---|
| WidgetPayloadResolver | 🟡 Stub | Widget library Maven coords |
| QueryExecutor (Snowflake) | 🟡 Stub | Snowflake JDBC URL + credentials |
| QueryExecutor (OpenSearch) | 🟡 Stub | OpenSearch client library + endpoint |
| Bedrock/Claude | 🟢 Real | Working (uses existing BedrockConfig) |
| RCA agent loop | 🟢 Real | Working |
| Risk snapshot | 🟢 Real | Static JSON file |
| Coach trigger | 🟢 Real | Uses Bedrock (Haiku 4.5) |
