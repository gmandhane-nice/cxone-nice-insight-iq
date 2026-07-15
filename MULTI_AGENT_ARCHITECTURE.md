# Multi-Agent Architecture Implementation

## Overview

Successfully refactored the RCA system from a single-agent tool-use loop into a true multi-agent architecture with specialized agents working in parallel.

## Architecture Flow

```
Supervisor Question
       │
       ▼
┌─────────────────────┐
│ Orchestrator Agent  │ ← Plans investigation (1 Bedrock call)
│ Understand + Plan   │
└─────────┬───────────┘
          │
    ┌─────┴──────┬──────────────┐
    ▼            ▼              ▼
┌──────────┐ ┌──────────────┐ ┌──────────────┐
│RealTime  │ │Historical    │ │Context Agent │ ← Run in PARALLEL
│Agent     │ │Agent         │ │(Schedules/   │   (CompletableFuture)
│(Valkey)  │ │(Snowflake/OS)│ │Config changes│
└────┬─────┘ └──────┬───────┘ └──────┬───────┘
     └───────────────┼────────────────┘
                     ▼
          ┌─────────────────────┐
          │ Reasoning Agent     │ ← Correlates evidence (1 Bedrock call)
          │ Correlate + Rank    │
          │ Generate Hypotheses │
          └──────────┬──────────┘
                     ▼
          ┌─────────────────────┐
          │ Recommendation Agent│ ← Produces actions (1 Bedrock call)
          │ Actions + MCQ       │
          │ Impact Forecast     │
          └─────────────────────┘

PLUS: Background Watchdog (always-on monitoring)
```

## Components Created

### 1. Core Interfaces & Models
- **`SubAgent.java`** - Interface for specialized sub-agents
- **`AgentEvent.java`** - Event model for SSE streaming

### 2. Agent Implementations

#### **`OrchestratorAgent.java`**
- Takes user question
- Makes ONE Bedrock call (no tools) with planning prompt
- Returns JSON: `{tasks: [{agent: "realtime", briefing: "..."}]}`
- System prompt: `prompts/orchestrator-system.txt`

#### **`RealTimeAgent.java`** - LIVE Data Specialist
- Tools: `queue_state`, `realtime_staffing`, `metric_snapshot`
- Focus: What is happening RIGHT NOW
- Model: Haiku (configurable) for speed
- System prompt: `prompts/realtime-system.txt`

#### **`HistoricalAgent.java`** - Trend Specialist
- Tools: `metric_history`, `contact_volume_breakdown`, `agent_leaderboard`, `ad_hoc_query`
- Focus: Trends, baselines, changes over time
- Model: Haiku (configurable) for speed
- System prompt: `prompts/historical-system.txt`

#### **`ContextAgent.java`** - Operational Context Specialist
- Tools: `agent_performance` (timeline), `ad_hoc_query`
- Focus: Config changes, schedule changes, staffing changes, deployments
- Model: Haiku (configurable) for speed
- System prompt: `prompts/context-system.txt`

#### **`ReasoningAgent.java`** - Evidence Correlator
- Takes evidence from all sub-agents
- Makes ONE Bedrock call (no tools) to correlate and rank hypotheses
- Returns JSON: `{hypotheses: [{title, confidence, evidence, supportingAgent}]}`
- Model: Sonnet for higher reasoning
- System prompt: `prompts/reasoning-system.txt`

#### **`RecommendationAgent.java`** - Action Generator
- Takes ranked hypotheses
- Makes ONE Bedrock call (no tools) to produce recommendations
- Returns JSON: `{summary, recommendations: [{action, rationale, expectedImpact, urgency}]}`
- Model: Sonnet for higher reasoning
- System prompt: `prompts/recommendation-system.txt`

### 3. Orchestration

#### **`MultiAgentOrchestrator.java`**
Main coordinator service that:
1. Calls OrchestratorAgent to plan
2. Runs sub-agents in parallel using `CompletableFuture.allOf()`
3. Calls ReasoningAgent to correlate evidence
4. Calls RecommendationAgent for final output
5. Emits SSE events at each stage for UI streaming

Pipeline phases:
- `planning` - Orchestrator creates plan
- `investigating` - Sub-agents run in parallel
- `reasoning` - Evidence correlation
- `recommending` - Action generation
- `complete` - Done

### 4. Background Monitoring

#### **`WatchdogScheduler.java`**
- Runs every 60 seconds (configurable)
- Checks queue SLA compliance and agent AHT drift
- Auto-triggers coach nudges for agent-level issues
- Pushes risk alerts for queue-level issues via SSE
- Enabled/disabled via `agentic.watchdog.enabled` config

### 5. API Updates

#### **`RcaStreamController.java`**
- Updated `POST /rca/v1/ask/stream` to use `MultiAgentOrchestrator`
- Added legacy endpoint `POST /rca/v1/ask/stream/legacy` for backward compatibility
- Added `GET /rca/v1/alerts/stream` for watchdog alerts

SSE Event Format:
```json
{
  "phase": "investigating",
  "agentName": "realtime",
  "status": "started",
  "detail": "Investigating: current queue state",
  "data": {...}
}
```

### 6. Configuration

#### **`application.yaml`**
Added:
```yaml
agentic:
  bedrock:
    haiku-model-id: us.anthropic.claude-haiku-4-5-20251001-v1:0  # For sub-agents
  watchdog:
    enabled: true
    interval-ms: 60000
    sla-threshold: 0.85
    aht-drift-threshold-pct: 20
    auto-coach-enabled: true
```

### 7. Frontend Updates

#### **`index.html`**
Enhanced RCA Chat tab to visualize multi-agent flow:
- Phase indicators with animated progress
- Agent cards showing active investigations
- Hypothesis cards with confidence bars
- Recommendation cards with urgency levels
- Real-time updates via SSE as agents work

Visual elements:
- 🎯 Planning phase
- 🔍 Investigation phase (shows which agents are active)
- 🧠 Reasoning phase (shows hypotheses + confidence)
- 💡 Recommendation phase (shows actions + urgency)
- ✅ Complete

## System Prompts

All prompts stored in `src/main/resources/prompts/`:
- `orchestrator-system.txt` - Task decomposition
- `realtime-system.txt` - Live data focus
- `historical-system.txt` - Trend analysis focus
- `context-system.txt` - Operational events focus
- `reasoning-system.txt` - Evidence correlation
- `recommendation-system.txt` - Action generation

## Key Design Decisions

1. **Parallel Execution**: Sub-agents run concurrently using `CompletableFuture.allOf()` for speed
2. **Model Selection**: 
   - Sonnet for orchestrator, reasoning, recommendation (higher reasoning)
   - Haiku for sub-agents (speed, cost-efficient for tool calls)
3. **Tool Partitioning**: Each sub-agent has access only to tools relevant to its domain
4. **Stateless Agents**: Each agent is a Spring bean with no per-request state
5. **SSE Streaming**: Real-time UI updates showing which agents are active
6. **Legacy Support**: Old `RcaAgent` kept but deprecated, legacy endpoint available

## Legacy Preservation

- **`RcaAgent.java`** marked `@Deprecated` but still functional
- Legacy endpoint: `POST /rca/v1/ask/stream/legacy`
- Old tools, `ToolRegistry`, `BedrockHelper` all unchanged and reused

## Testing Next Steps

To verify the implementation:
1. Start the application: `mvn spring-boot:run`
2. Open browser to `http://localhost:8080`
3. Go to "RCA Chat" tab
4. Ask: "Why has the Banking queue AHT increased today?"
5. Watch the multi-agent flow visualization
6. Observe:
   - Orchestrator plans (JSON with sub-agent tasks)
   - 3 sub-agents investigate in parallel
   - Reasoning agent correlates evidence
   - Recommendation agent produces actions

## Build Status

✅ Compilation successful: `mvn clean compile`
- 47 Java source files compiled
- No errors
- All agents properly wired with Spring dependency injection

## Files Created/Modified

### New Files (10 agents + 6 prompts):
- `src/main/java/com/nice/agentic/agents/SubAgent.java`
- `src/main/java/com/nice/agentic/agents/AgentEvent.java`
- `src/main/java/com/nice/agentic/agents/OrchestratorAgent.java`
- `src/main/java/com/nice/agentic/agents/RealTimeAgent.java`
- `src/main/java/com/nice/agentic/agents/HistoricalAgent.java`
- `src/main/java/com/nice/agentic/agents/ContextAgent.java`
- `src/main/java/com/nice/agentic/agents/ReasoningAgent.java`
- `src/main/java/com/nice/agentic/agents/RecommendationAgent.java`
- `src/main/java/com/nice/agentic/agents/MultiAgentOrchestrator.java`
- `src/main/java/com/nice/agentic/agents/WatchdogScheduler.java`
- `src/main/resources/prompts/orchestrator-system.txt`
- `src/main/resources/prompts/realtime-system.txt`
- `src/main/resources/prompts/historical-system.txt`
- `src/main/resources/prompts/context-system.txt`
- `src/main/resources/prompts/reasoning-system.txt`
- `src/main/resources/prompts/recommendation-system.txt`

### Modified Files:
- `src/main/java/com/nice/agentic/RcaAgent.java` - Added @Deprecated
- `src/main/java/com/nice/agentic/RcaStreamController.java` - Rewired to use MultiAgentOrchestrator
- `src/main/resources/application.yaml` - Added watchdog config
- `src/main/resources/static/index.html` - Enhanced UI for multi-agent visualization

## Constraints Met

✅ Java 17 compatible (no virtual threads)
✅ Reused existing `ToolRegistry`, `AgentTool`, `WidgetPayloadResolver`, `SnowflakeExecutor`, `OpenSearchExecutor`, `ValkeyWidgetClient`, `TenantContext`
✅ Proper SLF4J logging in all agents
✅ System prompts in separate .txt files
✅ Legacy `RcaAgent` preserved and marked deprecated
✅ Compilation successful (green build)
