# Architecture Flowchart - Agentic RCA Sample Platform

This diagram illustrates the system architecture and data flow for the AI-powered contact center analytics platform.

## System Architecture Diagram

```mermaid
flowchart TB
    subgraph Frontend["Frontend Layer"]
        UI[Single Page Application<br/>HTML + JavaScript + Charts]
    end

    subgraph API["Spring Boot Application Layer"]
        subgraph Controllers["REST Controllers"]
            C1[Smart Overflow<br/>Controller]
            C2[Demand Forecast<br/>Controller]
            C3[Burnout Risk<br/>Controller]
            C4[Anomaly Detection<br/>Controller]
            C5[Staffing Simulator<br/>Controller]
            C6[Shrinkage<br/>Controller]
            C7[Deflection<br/>Controller]
            C8[ROI Dashboard<br/>Controller]
            C9[Daily Briefing<br/>Controller]
        end

        subgraph Core["Shared Infrastructure"]
            TC[TenantContext<br/>Multi-tenant Isolation]
            SE[SnowflakeExecutor<br/>Query Execution Layer]
            BH[BedrockHelper<br/>LLM Integration]
            EH[GlobalExceptionHandler<br/>Error Handling]
        end
    end

    subgraph External["External Systems"]
        SF[(Snowflake Data Warehouse<br/>AGENT_CONTACT_FACT_VIEW<br/>USER_DIM_VIEW<br/>SKILL_SCD_DIM_VIEW)]
        BR[AWS Bedrock<br/>Claude LLM<br/>Converse API]
    end

    subgraph Analytics["Analytics Processing"]
        A1[Parallel Query Execution<br/>CompletableFuture]
        A2[Statistical Models<br/>Z-Score, Erlang-C, CV]
        A3[Risk Scoring<br/>Multi-factor Algorithms]
        A4[Time Series Analysis<br/>28-day Patterns]
    end

    UI -->|HTTP REST| Controllers
    
    C1 & C2 & C3 & C4 & C5 & C6 & C7 & C8 & C9 --> TC
    C1 & C2 & C3 & C4 & C5 & C6 & C7 & C8 & C9 --> SE
    C9 --> BH
    
    TC -->|Tenant Scoping| SE
    SE -->|JDBC Queries| SF
    BH -->|Converse API| BR
    
    SE --> A1
    A1 --> A2
    A2 --> A3
    A3 --> A4
    
    A4 -->|Insights + Metrics| Controllers
    BR -->|AI-Generated Text| C9
    SF -->|Real-time Data| A1
    
    EH -.->|Fallback to Mock| Controllers

    style Frontend fill:#e1f5ff
    style API fill:#fff4e6
    style External fill:#f3e5f5
    style Analytics fill:#e8f5e9
    style Core fill:#fff9c4
```

## Data Flow Sequence

```mermaid
flowchart LR
    A[Client Request] --> B{Tenant ID<br/>in Header?}
    B -->|Yes| C[Extract Tenant]
    B -->|No| D[Use Default Tenant]
    C & D --> E[TenantContext]
    
    E --> F{Snowflake<br/>Available?}
    F -->|Yes| G[Execute SQL Query]
    F -->|No| H[Return Mock Data]
    
    G --> I[Parse Results]
    I --> J{Requires<br/>AI Enhancement?}
    
    J -->|Yes| K[Call Bedrock LLM]
    J -->|No| L[Apply Analytics]
    
    K --> M[Generate Insights]
    L --> M
    
    M --> N[Assemble Response]
    H --> N
    
    N --> O[JSON Response]
    
    style A fill:#4CAF50
    style O fill:#2196F3
    style F fill:#FF9800
    style J fill:#9C27B0
```

## Module Architecture Pattern

Each analytics module follows this consistent pattern:

```mermaid
flowchart TB
    subgraph Module["Analytics Module Pattern"]
        Controller[Controller<br/>@RestController]
        
        Controller --> Check{Snowflake<br/>Configured?}
        
        Check -->|No| Mock[buildMockResponse]
        Check -->|Yes| Live[buildLiveResponse]
        
        Live --> Query[Execute Snowflake Query]
        Query --> Process[Process Data]
        Process --> Analytics[Apply Module Logic]
        
        Analytics --> Response[Assemble Response]
        Mock --> Response
        
        Response --> Return[Return JSON]
    end
    
    style Controller fill:#42A5F5
    style Check fill:#FFA726
    style Analytics fill:#66BB6A
    style Response fill:#AB47BC
```

## Key Design Principles

1. **Graceful Degradation**: Every module has mock data fallback
2. **Tenant Isolation**: All queries filtered by `_TENANT_ID`
3. **Parallel Execution**: CompletableFuture for concurrent Snowflake queries
4. **Single Responsibility**: Each controller handles one analytics capability
5. **Fail-Safe**: No external dependency failure causes 500 errors

## Technology Stack Flow

```mermaid
flowchart LR
    subgraph Client
        Browser[Web Browser]
    end
    
    subgraph App["Spring Boot 3.3.4"]
        REST[REST Controllers]
        Service[Service Layer]
        Data[Data Access Layer]
    end
    
    subgraph DataStore["Data Sources"]
        Snow[Snowflake JDBC 3.24.2]
        Cache[HikariCP 6.0.0<br/>Connection Pool]
    end
    
    subgraph AI["AI Layer"]
        Bedrock[AWS Bedrock<br/>SDK 2.28.11]
        Claude[Claude Sonnet 4.5]
    end
    
    Browser -->|HTTPS| REST
    REST --> Service
    Service --> Data
    Data --> Cache
    Cache --> Snow
    Service --> Bedrock
    Bedrock --> Claude
    
    style Browser fill:#E3F2FD
    style App fill:#FFF3E0
    style DataStore fill:#E8F5E9
    style AI fill:#F3E5F5
```

## Security & Multi-Tenancy

```mermaid
flowchart TB
    Request[Incoming Request] --> Header{X-Tenant-ID<br/>Header Present?}
    
    Header -->|Yes| Extract[Extract Tenant ID]
    Header -->|No| Query{tenantId Query<br/>Parameter?}
    
    Query -->|Yes| Extract
    Query -->|No| Default[Use Default:<br/>demo_tenant_001]
    
    Extract & Default --> Store[Store in<br/>TenantContext]
    
    Store --> SQL[Generate SQL]
    SQL --> Filter[Add WHERE Clause:<br/>_TENANT_ID = '...']
    Filter --> Execute[Execute Query]
    
    Execute --> Validate[Validate Results]
    Validate --> Return[Return Response]
    
    style Header fill:#FF9800
    style Filter fill:#F44336
    style Validate fill:#4CAF50
```

## Deployment Architecture

```mermaid
flowchart TB
    subgraph AWS["AWS Cloud"]
        subgraph ECS["ECS Fargate"]
            App1[App Instance 1]
            App2[App Instance 2]
            App3[App Instance 3]
        end
        
        ALB[Application Load Balancer]
        Bedrock[AWS Bedrock<br/>Claude Model]
        
        ALB --> App1 & App2 & App3
        App1 & App2 & App3 --> Bedrock
    end
    
    subgraph Snowflake["Snowflake Cloud"]
        DW[Data Warehouse<br/>Analytics Views]
    end
    
    subgraph Monitoring["Observability"]
        CW[CloudWatch Logs]
        Metrics[CloudWatch Metrics]
    end
    
    Internet[Internet] --> ALB
    App1 & App2 & App3 --> DW
    App1 & App2 & App3 --> CW
    App1 & App2 & App3 --> Metrics
    
    style AWS fill:#FF9900
    style Snowflake fill:#29B5E8
    style Monitoring fill:#146EB4
```

## Analytics Processing Pipeline

```mermaid
flowchart LR
    subgraph Input["Data Input"]
        Raw[Raw Contact Data<br/>from Snowflake]
    end
    
    subgraph Processing["Analytics Processing"]
        Clean[Data Cleaning<br/>& Validation]
        Agg[Aggregation<br/>CTEs & Window Functions]
        Calc[Calculation<br/>Metrics & Scores]
        Model[Statistical Models<br/>Z-Score, Erlang-C]
    end
    
    subgraph Enhancement["AI Enhancement"]
        LLM[LLM Processing<br/>via Bedrock]
        Template[Template-based<br/>Fallback]
    end
    
    subgraph Output["Response"]
        JSON[Structured JSON<br/>with Insights]
    end
    
    Raw --> Clean
    Clean --> Agg
    Agg --> Calc
    Calc --> Model
    
    Model --> LLM
    Model --> Template
    
    LLM --> JSON
    Template --> JSON
    
    style Input fill:#E3F2FD
    style Processing fill:#FFF3E0
    style Enhancement fill:#F3E5F5
    style Output fill:#E8F5E9
```

---

**Diagram Notes:**
- All diagrams use Mermaid syntax for easy rendering in GitHub, GitLab, and documentation tools
- Flowcharts illustrate request flow, data processing, and system interactions
- Color coding: Blue (input), Orange (processing), Purple (AI), Green (output), Yellow (infrastructure)
