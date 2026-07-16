# Sequence Diagrams - Agentic RCA Sample Platform

This document provides detailed sequence diagrams for key user flows and system interactions.

## 1. Standard Analytics Request Flow

This sequence shows the typical flow for any analytics endpoint (e.g., Smart Overflow, Burnout Risk, Anomaly Detection).

```mermaid
sequenceDiagram
    actor User
    participant Browser
    participant Controller
    participant TenantContext
    participant SnowflakeExecutor
    participant Snowflake
    participant Analytics
    
    User->>Browser: Click "Get Burnout Risk"
    Browser->>Controller: GET /burnout/risk<br/>X-Tenant-ID: tenant_abc123
    
    Controller->>TenantContext: getTenantId()
    TenantContext-->>Controller: tenant_abc123
    
    Controller->>SnowflakeExecutor: isConfigured()
    SnowflakeExecutor-->>Controller: true
    
    Controller->>SnowflakeExecutor: execute(sql)
    SnowflakeExecutor->>Snowflake: JDBC Query<br/>WHERE _TENANT_ID = 'tenant_abc123'
    Snowflake-->>SnowflakeExecutor: Result Set (rows)
    SnowflakeExecutor-->>Controller: List<Map<String, Object>>
    
    Controller->>Analytics: scoreAgents(rows)
    Analytics->>Analytics: Calculate AHT trend (0-30pts)
    Analytics->>Analytics: Calculate refusal rate (0-25pts)
    Analytics->>Analytics: Calculate volume drop (0-25pts)
    Analytics->>Analytics: Calculate consistency/CV (0-20pts)
    Analytics->>Analytics: Determine risk level
    Analytics-->>Controller: List<ScoredAgent>
    
    Controller->>Controller: buildResponseEnvelope()
    Controller-->>Browser: JSON Response<br/>{summary, agents, insights}
    Browser->>Browser: Render charts and tables
    Browser-->>User: Display results
```

## 2. AI-Enhanced Daily Briefing Flow

This sequence demonstrates the LLM integration for the Daily Briefing feature.

```mermaid
sequenceDiagram
    actor Supervisor
    participant Browser
    participant BriefingController
    participant TenantContext
    participant SnowflakeExecutor
    participant Snowflake
    participant BedrockHelper
    participant Bedrock
    
    Supervisor->>Browser: Open Daily Briefing Tab
    Browser->>BriefingController: GET /briefing/today<br/>X-Tenant-ID: tenant_xyz
    
    BriefingController->>TenantContext: getTenantId()
    TenantContext-->>BriefingController: tenant_xyz
    
    BriefingController->>SnowflakeExecutor: execute(briefingSql)
    Note over SnowflakeExecutor,Snowflake: Single CTE query:<br/>- Yesterday's contacts<br/>- Top skills by volume<br/>- SLA issues<br/>- Agent AHT changes<br/>- Queue depths
    SnowflakeExecutor->>Snowflake: Complex CTE Query
    Snowflake-->>SnowflakeExecutor: Aggregated Metrics
    SnowflakeExecutor-->>BriefingController: metrics
    
    BriefingController->>BriefingController: buildUserPrompt(metrics)
    BriefingController->>BedrockHelper: converse(systemPrompt, userPrompt)
    
    BedrockHelper->>Bedrock: Converse API Request<br/>Model: Claude Sonnet 4.5<br/>Temperature: 0.4
    Note over Bedrock: LLM analyzes metrics<br/>and generates structured<br/>briefing JSON
    Bedrock-->>BedrockHelper: JSON Response<br/>{headline, priorities, wins, risks, recommendation}
    BedrockHelper-->>BriefingController: llmBriefing
    
    BriefingController->>BriefingController: Parse JSON
    BriefingController->>BriefingController: Validate structure
    BriefingController-->>Browser: JSON Response<br/>{generatedAt, briefing, metrics}
    
    Browser->>Browser: Render briefing cards
    Browser-->>Supervisor: Display personalized briefing
```

## 3. What-If Staffing Simulation Flow

This sequence shows the interactive staffing simulator with Erlang-C calculations.

```mermaid
sequenceDiagram
    actor Supervisor
    participant Browser
    participant SimulatorController
    participant SnowflakeExecutor
    participant Snowflake
    participant ErlangC
    
    Supervisor->>Browser: Request current staffing state
    Browser->>SimulatorController: GET /simulator/current-state
    
    SimulatorController->>SnowflakeExecutor: execute(currentStateSql)
    SnowflakeExecutor->>Snowflake: Query last 7 days:<br/>- Contacts per hour<br/>- Avg AHT<br/>- Active agents per skill
    Snowflake-->>SnowflakeExecutor: Skill metrics
    SnowflakeExecutor-->>SimulatorController: skills data
    
    loop For each skill
        SimulatorController->>ErlangC: predictSla(agents, volume, AHT)
        ErlangC->>ErlangC: Calculate traffic intensity
        ErlangC->>ErlangC: Compute Erlang-C probability
        ErlangC->>ErlangC: Calculate predicted SLA
        ErlangC-->>SimulatorController: currentSla
    end
    
    SimulatorController-->>Browser: {skills: [{skillName, agents, SLA}]}
    Browser-->>Supervisor: Display current state table
    
    Note over Supervisor: User adjusts agents:<br/>Billing -2 agents<br/>Technical +2 agents
    
    Supervisor->>Browser: Click "Simulate Changes"
    Browser->>SimulatorController: POST /simulator/simulate<br/>{changes: [{skillNo: 1042, delta: -2}, ...]}
    
    loop For each skill change
        SimulatorController->>ErlangC: predictSla(currentAgents, volume, AHT)
        ErlangC-->>SimulatorController: beforeSla
        
        SimulatorController->>ErlangC: predictSla(currentAgents + delta, volume, AHT)
        ErlangC-->>SimulatorController: afterSla
        
        SimulatorController->>SimulatorController: Calculate slaDelta
        SimulatorController->>SimulatorController: computeVerdict(slaDelta)
    end
    
    SimulatorController->>SimulatorController: buildRecommendation(results)
    SimulatorController-->>Browser: {results, netImpact, recommendation}
    
    Browser->>Browser: Render before/after comparison
    Browser->>Browser: Highlight verdict (safe/caution/NOT recommended)
    Browser-->>Supervisor: Display simulation results
```

## 4. Smart Overflow Parallel Query Flow

This sequence demonstrates parallel query execution using CompletableFuture.

```mermaid
sequenceDiagram
    actor Supervisor
    participant Browser
    participant OverflowController
    participant SnowflakeExecutor
    participant Snowflake
    
    Supervisor->>Browser: Request overflow recommendations
    Browser->>OverflowController: GET /risk/overflow/recommendations
    
    OverflowController->>SnowflakeExecutor: execute(atRiskSkillsSql)
    SnowflakeExecutor->>Snowflake: Query skills with:<br/>- Queue depth > 5<br/>- Growing volume trend
    Snowflake-->>SnowflakeExecutor: Top 10 at-risk skills
    SnowflakeExecutor-->>OverflowController: atRiskSkills
    
    OverflowController->>OverflowController: Take top 5 skills
    
    par Parallel proficiency queries
        OverflowController->>SnowflakeExecutor: execute(proficientAgentsSql, skill1)
        SnowflakeExecutor->>Snowflake: Find agents with AHT <= team avg<br/>for skill 1
        Snowflake-->>SnowflakeExecutor: Proficient agents
        SnowflakeExecutor-->>OverflowController: proficientAgents1
    and
        OverflowController->>SnowflakeExecutor: execute(proficientAgentsSql, skill2)
        SnowflakeExecutor->>Snowflake: Find agents with AHT <= team avg<br/>for skill 2
        Snowflake-->>SnowflakeExecutor: Proficient agents
        SnowflakeExecutor-->>OverflowController: proficientAgents2
    and
        OverflowController->>SnowflakeExecutor: execute(proficientAgentsSql, skill3)
        SnowflakeExecutor->>Snowflake: Find agents with AHT <= team avg<br/>for skill 3
        Snowflake-->>SnowflakeExecutor: Proficient agents
        SnowflakeExecutor-->>OverflowController: proficientAgents3
    end
    
    Note over OverflowController: CompletableFuture.allOf().join()<br/>Wait for all parallel queries
    
    loop For each at-risk skill
        OverflowController->>OverflowController: Calculate agents needed<br/>= queueDepth / 5
        OverflowController->>OverflowController: Select top N proficient agents
        OverflowController->>OverflowController: Predict queue reduction<br/>= min(80%, N * 15%)
        OverflowController->>OverflowController: Build recommendation
    end
    
    OverflowController-->>Browser: {recommendations, totalAtRiskSkills, totalCandidates}
    Browser-->>Supervisor: Display agent reassignment recommendations
```

## 5. Anomaly Detection Z-Score Analysis Flow

This sequence shows the statistical anomaly detection process.

```mermaid
sequenceDiagram
    actor Supervisor
    participant Browser
    participant AnomalyController
    participant SnowflakeExecutor
    participant Snowflake
    participant ZScoreAnalyzer
    
    Supervisor->>Browser: View Anomaly Detection
    Browser->>AnomalyController: GET /anomaly/detect
    
    par Parallel anomaly queries
        AnomalyController->>SnowflakeExecutor: execute(skillAnomaliesSql)
        SnowflakeExecutor->>Snowflake: CTE Query:<br/>1. Daily skill stats (last 22 days)<br/>2. Baseline (21 days): avg, stddev<br/>3. Recent (today): current values
        Snowflake-->>SnowflakeExecutor: Skill metrics with baselines
        SnowflakeExecutor-->>AnomalyController: skillRows
    and
        AnomalyController->>SnowflakeExecutor: execute(agentAnomaliesSql)
        SnowflakeExecutor->>Snowflake: CTE Query:<br/>1. Agent daily AHT<br/>2. Baseline per agent<br/>3. Today's AHT
        Snowflake-->>SnowflakeExecutor: Agent metrics with baselines
        SnowflakeExecutor-->>AnomalyController: agentRows
    end
    
    loop For each skill row
        AnomalyController->>ZScoreAnalyzer: calculateZScore(volume, baseline)
        ZScoreAnalyzer->>ZScoreAnalyzer: z = (current - mean) / stddev
        ZScoreAnalyzer-->>AnomalyController: zVolume
        
        alt |zVolume| >= 2.0
            AnomalyController->>AnomalyController: Create volume anomaly
            AnomalyController->>AnomalyController: Severity = critical if |z| >= 3.0
        end
        
        AnomalyController->>ZScoreAnalyzer: calculateZScore(AHT, baseline)
        ZScoreAnalyzer-->>AnomalyController: zAHT
        
        alt |zAHT| >= 2.0
            AnomalyController->>AnomalyController: Create AHT anomaly
        end
        
        AnomalyController->>ZScoreAnalyzer: calculateZScore(refusalRate, baseline)
        ZScoreAnalyzer-->>AnomalyController: zRefusal
        
        alt |zRefusal| >= 2.0
            AnomalyController->>AnomalyController: Create refusal anomaly
        end
    end
    
    loop For each agent row
        AnomalyController->>ZScoreAnalyzer: calculateZScore(agentAHT, baseline)
        ZScoreAnalyzer-->>AnomalyController: zAgent
        
        alt |zAgent| >= 2.0
            AnomalyController->>AnomalyController: Create agent anomaly
        end
    end
    
    AnomalyController->>AnomalyController: Sort by severity DESC, |z| DESC
    AnomalyController->>AnomalyController: Limit to top 20
    AnomalyController->>AnomalyController: Assign anomaly IDs
    
    AnomalyController-->>Browser: {summary, anomalies: [{id, type, severity, zScore, ...}]}
    Browser->>Browser: Render anomaly cards with color coding
    Browser-->>Supervisor: Display critical and warning anomalies
```

## 6. Graceful Fallback Flow (Snowflake Unavailable)

This sequence demonstrates the resilient fallback mechanism.

```mermaid
sequenceDiagram
    actor User
    participant Browser
    participant Controller
    participant SnowflakeExecutor
    participant Snowflake
    participant MockDataStore
    
    User->>Browser: Request analytics
    Browser->>Controller: GET /burnout/risk
    
    Controller->>SnowflakeExecutor: isConfigured()
    SnowflakeExecutor-->>Controller: false
    Note over SnowflakeExecutor: Environment variables not set
    
    Controller->>MockDataStore: buildMockResponse()
    MockDataStore->>MockDataStore: Generate realistic data<br/>- 5 sample agents<br/>- Varied risk scores<br/>- Detailed signals
    MockDataStore-->>Controller: Mock response JSON
    
    Controller-->>Browser: JSON Response (mock)
    Note over Browser: Response includes same schema<br/>as live data - UI renders normally
    Browser-->>User: Display mock analytics
    
    Note over User: Alternatively, Snowflake is configured<br/>but connection fails
    
    User->>Browser: Request analytics (retry)
    Browser->>Controller: GET /forecast/demand
    
    Controller->>SnowflakeExecutor: isConfigured()
    SnowflakeExecutor-->>Controller: true
    
    Controller->>SnowflakeExecutor: execute(sql)
    SnowflakeExecutor->>Snowflake: JDBC Query
    Snowflake--XSnowflakeExecutor: Connection timeout
    SnowflakeExecutor-->>Controller: throws SQLException
    
    Controller->>Controller: catch (Exception e)
    Controller->>MockDataStore: buildMockResponse()
    MockDataStore-->>Controller: Mock response JSON
    
    Controller-->>Browser: JSON Response (fallback)
    Browser-->>User: Display fallback data with info banner
```

## 7. ROI Dashboard Aggregation Flow

This sequence shows the comprehensive ROI calculation across all modules.

```mermaid
sequenceDiagram
    actor Executive
    participant Browser
    participant RoiController
    participant SnowflakeExecutor
    participant Snowflake
    participant CostCalculator
    
    Executive->>Browser: View ROI Dashboard
    Browser->>RoiController: GET /roi/summary
    
    RoiController->>SnowflakeExecutor: execute(roiAggregateSql)
    Note over SnowflakeExecutor,Snowflake: Single complex CTE query:<br/>- Overflow metrics (breach contacts)<br/>- Coaching metrics (AHT improvement)<br/>- Deflection metrics (simple contacts)<br/>- Shrinkage metrics (idle hours)<br/>- Attrition metrics (burnout signals)
    
    SnowflakeExecutor->>Snowflake: Execute 6-way CROSS JOIN CTE
    Snowflake-->>SnowflakeExecutor: Single row with all metrics
    SnowflakeExecutor-->>RoiController: metrics
    
    RoiController->>CostCalculator: calculateOverflowSavings(breachContacts, atRiskQueues)
    CostCalculator->>CostCalculator: Apply cost model:<br/>$0.50/contact + $500/queue penalty
    CostCalculator-->>RoiController: overflowSavings
    
    RoiController->>CostCalculator: calculateCoachingSavings(hoursSaved)
    CostCalculator->>CostCalculator: hoursSaved * $25/hr
    CostCalculator-->>RoiController: coachingSavings
    
    RoiController->>CostCalculator: calculateDeflectionSavings(automatableContacts)
    CostCalculator->>CostCalculator: contacts * 0.4 deflection * $0.50/contact
    CostCalculator-->>RoiController: deflectionSavings
    
    RoiController->>CostCalculator: calculateShrinkageSavings(excessIdleHours)
    CostCalculator->>CostCalculator: excessHours * $25/hr
    CostCalculator-->>RoiController: shrinkageSavings
    
    RoiController->>CostCalculator: calculateAttritionSavings(atRiskAgents)
    CostCalculator->>CostCalculator: agents * 0.3 prevention * $8000 replacement
    CostCalculator-->>RoiController: attritionSavings
    
    RoiController->>RoiController: Aggregate total monthly savings
    RoiController->>RoiController: Multiply by 12 for annual
    RoiController->>RoiController: Build breakdown with details
    
    RoiController-->>Browser: {totalMonthlySavings, totalAnnualSavings, roiBreakdown, keyMetrics}
    Browser->>Browser: Render savings cards with icons
    Browser->>Browser: Display pie chart breakdown
    Browser-->>Executive: Show $1.53M annual savings
```

## 8. Multi-Tenant Request Isolation Flow

This sequence demonstrates tenant context extraction and SQL scoping.

```mermaid
sequenceDiagram
    actor TenantA as Tenant A User
    actor TenantB as Tenant B User
    participant Browser
    participant Controller
    participant TenantContext
    participant SnowflakeExecutor
    participant Snowflake
    
    TenantA->>Browser: Request data
    Browser->>Controller: GET /burnout/risk<br/>X-Tenant-ID: tenant_a_123
    
    Controller->>TenantContext: setTenantId("tenant_a_123")
    TenantContext->>TenantContext: Store in ThreadLocal
    TenantContext-->>Controller: OK
    
    Controller->>TenantContext: getTenantId()
    TenantContext-->>Controller: "tenant_a_123"
    
    Controller->>SnowflakeExecutor: execute(sql)
    SnowflakeExecutor->>SnowflakeExecutor: Inject WHERE clause:<br/>_TENANT_ID = 'tenant_a_123'
    SnowflakeExecutor->>Snowflake: SELECT ... WHERE _TENANT_ID = 'tenant_a_123'
    Snowflake-->>SnowflakeExecutor: Tenant A data only
    SnowflakeExecutor-->>Controller: results
    
    Controller-->>Browser: JSON (Tenant A data)
    Browser-->>TenantA: Display Tenant A analytics
    
    Note over TenantA,TenantB: Concurrent request from different tenant
    
    TenantB->>Browser: Request data
    Browser->>Controller: GET /burnout/risk<br/>X-Tenant-ID: tenant_b_456
    
    Controller->>TenantContext: setTenantId("tenant_b_456")
    TenantContext->>TenantContext: Store in separate ThreadLocal
    TenantContext-->>Controller: OK
    
    Controller->>TenantContext: getTenantId()
    TenantContext-->>Controller: "tenant_b_456"
    
    Controller->>SnowflakeExecutor: execute(sql)
    SnowflakeExecutor->>SnowflakeExecutor: Inject WHERE clause:<br/>_TENANT_ID = 'tenant_b_456'
    SnowflakeExecutor->>Snowflake: SELECT ... WHERE _TENANT_ID = 'tenant_b_456'
    Snowflake-->>SnowflakeExecutor: Tenant B data only
    SnowflakeExecutor-->>Controller: results
    
    Controller-->>Browser: JSON (Tenant B data)
    Browser-->>TenantB: Display Tenant B analytics
    
    Note over TenantContext: ThreadLocal ensures<br/>tenant isolation per request
```

---

**Diagram Notes:**
- All sequence diagrams use Mermaid syntax for portability
- Timing annotations show async operations (par blocks) and iterations (loop blocks)
- Error paths demonstrate graceful degradation
- Multi-tenant isolation patterns ensure data security
