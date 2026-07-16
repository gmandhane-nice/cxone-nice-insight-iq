# InsightIQ — Agentic Decision Intelligence Platform

An AI-powered decision intelligence platform for contact center management. InsightIQ uses multi-agent AI orchestration to monitor operations 24/7, identify revenue leakage in real time, and prescribe exactly what to do about it.

---

## Executive Summary (for Senior Leadership)

InsightIQ transforms contact center operations from **reactive to proactive**. Instead of supervisors discovering problems after they happen, the platform identifies issues in real-time and prescribes exactly what to do.

**The Problem It Solves:**
- Supervisors today rely on static dashboards and manual analysis
- Problems are discovered after SLA failures, not before
- Staffing decisions are based on gut feeling, not data
- Agent burnout and attrition go undetected until it's too late
- Millions in savings opportunities remain hidden in operational data

**How It Works:**
- 9 AI modules run continuously, analyzing live contact center data
- Multi-agent AI orchestration (Claude on AWS Bedrock) generates insights in natural language
- Prescriptive recommendations tell supervisors *what to do*, not just *what happened*
- Mathematical models (Erlang-C, Z-score) provide statistical confidence

**Business Impact:**
- Quantified ROI across coaching, overflow, deflection, shrinkage, and attrition
- Prevents SLA failures through predictive alerting and prescriptive fixes
- Reduces agent turnover through early burnout detection
- Identifies automation opportunities to reduce contact volume
- Replaces manual reporting with AI-generated executive briefings

**Differentiators vs. Traditional Dashboards:**

| Traditional BI | InsightIQ |
|---------------|-----------|
| Shows what happened | Predicts what will happen |
| Requires manual analysis | AI investigates autonomously |
| Generic alerts | Prescribes the exact fix |
| Separate disconnected tools | 9 modules in one platform |
| Periodic reports | Real-time continuous monitoring |
| Descriptive | Prescriptive + Predictive |

**For a 3-minute walkthrough, see the auto-generated demo video:** `docs/auto-demo/output/final-demo.mp4`

---

## Table of Contents

- [What It Does](#what-it-does)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Running the Application](#running-the-application)
- [How to Use — Complete Tab Guide](#how-to-use--complete-tab-guide)
- [Recommended Daily Workflow](#recommended-daily-workflow)
- [API Reference](#api-reference)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Configuration](#configuration)
- [Key Algorithms](#key-algorithms)
- [Demo Video Generator](#demo-video-generator)
- [Troubleshooting](#troubleshooting)
- [Design Decisions](#design-decisions)
- [Contributing](#contributing)

---

## What It Does

InsightIQ provides **9 intelligent modules** in a single unified platform:

| # | Module | What It Does | Key Value |
|---|--------|-------------|-----------|
| 1 | **RCA Chat** | Natural language Q&A — ask anything about your contact center | Autonomous AI investigation |
| 2 | **ROI Dashboard** | Quantifies savings across all optimization levers | CFO-ready budget justification |
| 3 | **AI Briefing** | Daily AI-generated executive summary | Replaces 30-min morning standups |
| 4 | **Forecast** | Predicts contact volume for next 4 hours | Proactive staffing before surges |
| 5 | **Risk Monitor** | Real-time SLA health per skill + prescriptive fixes | Prevents service failures |
| 6 | **Coaching** | Identifies which agents need training on which skills | Targeted, not generic |
| 7 | **Burnout** | Behavioral risk scoring before agents quit | Prevents costly attrition |
| 8 | **Anomaly** | Z-score detection of unusual patterns | Catches what humans miss |
| 9 | **Simulator** | Erlang-C what-if staffing analysis | Test changes before executing |
| 10 | **Shrinkage** | Idle time analysis with cost quantification | Visibility drives accountability |
| 11 | **Deflection** | Identifies automation opportunities | Reduce volume, reduce cost |
| 12 | **Agent View** | Individual agent performance dashboard | Side-by-side comparison |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 17 · Spring Boot 3.3.4 |
| AI Engine | AWS Bedrock · Claude (Converse API) · Multi-agent orchestration |
| Data | Snowflake (read-only JDBC) · HikariCP connection pooling |
| Frontend | Single-page HTML/JS · Tabbed interface · Chart.js visualizations |
| Analytics | Z-score anomaly detection · Erlang-C queuing theory · Burnout risk scoring |
| Build | Maven 3.9+ · JUnit 5 |

---

## Prerequisites

Before you begin, ensure you have:

| Requirement | Version | Check Command |
|-------------|---------|---------------|
| Java | 17 or higher | `java -version` |
| Maven | 3.9 or higher | `mvn -version` |
| AWS CLI | 2.x (configured) | `aws sts get-caller-identity` |
| Git | Any recent | `git --version` |

**AWS permissions required:** `bedrock:InvokeModel` on Claude model in `us-west-2`.

**Optional:** Snowflake read-only credentials. Without Snowflake, the app runs with realistic mock data (fully functional for demos).

---

## Installation

### Step 1: Clone the Repository

```bash
git clone <repository-url>
cd agentic-rca-sample
```

### Step 2: Configure Snowflake (Optional)

Create `src/main/resources/application-dev.yaml`:

```yaml
snowflake:
  jdbc-url: jdbc:snowflake://<account>.snowflakecomputing.com
  username: <your-username>
  password: <your-password>
  warehouse: <warehouse-name>
  database: <database-name>
  schema: <schema-name>
```

> **IMPORTANT:** This file contains credentials. It is gitignored and must NEVER be committed.

### Step 3: Build

```bash
mvn clean install
```

This downloads all dependencies, compiles the code, and runs tests.

---

## Running the Application

### Option A: With Snowflake (live data)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Option B: Without Snowflake (mock data — great for demos)

```bash
mvn spring-boot:run
```

### Option C: Using the convenience script

```bash
./run-dev.sh
```

### Option D: Packaged JAR

```bash
mvn clean package -DskipTests
java -jar target/agentic-rca-sample-0.1.0.jar
```

The application starts on **http://localhost:8080**. Open this URL in your browser.

### Verify It's Running

```bash
curl http://localhost:8080/roi/summary
```

You should get a JSON response with ROI data.

---

## How to Use — Complete Tab Guide

Open http://localhost:8080 in your browser. The interface has a navigation bar with tabs for each module. Click any tab to switch.

---

### Tab 1: RCA Chat (Landing Page)

**What it is:** A conversational AI interface where you ask questions about your contact center in plain English.

**How to use:**
1. Type your question in the chat input box at the bottom
2. Click **Ask** or press Enter
3. Wait 3-8 seconds — the AI investigates your question autonomously
4. Read the structured response: findings, data, and recommendations

**Example questions you can ask:**
- "Which skills have the highest refusal rate?"
- "Top agents by average handle time this week"
- "Why is the billing queue building up?"
- "Which agents are taking excessive breaks?"
- "Compare performance of morning vs evening shifts"

**What happens behind the scenes:**
- AI parses your question
- Orchestrates the right SQL queries against live data
- Performs statistical analysis
- Generates actionable recommendations

**When to use:**
- When something seems wrong and you want to understand why
- When you have a specific operational question
- When you want data-backed answers without building reports manually

---

### Tab 2: ROI Dashboard

**What it is:** Shows the total dollar-value savings this platform generates, broken down by category.

**How to use:**
1. Click the **ROI** tab
2. See the total annual savings figure at the top
3. Scroll to see breakdown by category:
   - Coaching optimization (reduced AHT through targeted training)
   - Smart overflow (SLA breach prevention)
   - Contact deflection (automation savings)
   - Shrinkage reduction (idle time recovery)
   - Attrition prevention (reduced turnover costs)
4. View "vs Baseline" comparison showing improvement percentages

**When to use:**
- Executive presentations and budget reviews
- Proving platform ROI to leadership
- Monthly/quarterly business reporting
- Comparing before vs after platform deployment

---

### Tab 3: AI Briefing

**What it is:** A daily AI-generated executive summary of everything important in your contact center — like a morning newspaper.

**How to use:**
1. Click the **Briefing** tab
2. Click the **Generate Briefing** button
3. Wait 3-6 seconds for AI to analyze data and generate the briefing
4. Read the output:
   - **Headline:** One-sentence summary of the day's situation
   - **Priorities:** Ranked list (Critical / High / Medium) with details
   - **Wins:** Good news and improvements
   - **Risks:** Emerging problems to watch
   - **Top Recommendation:** Single most important action to take

**When to use:**
- First thing every morning to set daily priorities
- Before management meetings for quick situational awareness
- At shift handovers to brief incoming supervisors

---

### Tab 4: Forecast

**What it is:** Predicts how many customer contacts you'll receive in the next 4 hours so you can plan staffing.

**How to use:**
1. Click the **Forecast** tab
2. View the hourly prediction chart (predicted vs historical average)
3. Check for **SURGE ALERT** badges — these warn of upcoming spikes
4. Compare:
   - **Predicted Volume** vs **Historical Average**
   - **Current Agents** vs **Agents Needed**
5. If understaffed for upcoming hours, prepare backup agents

**Key indicators:**
- Green = adequate staffing
- Yellow = watch closely
- Red = understaffed, action needed

**When to use:**
- Planning lunch breaks and shift schedules
- Deciding when to call in backup agents
- Preparing for busy periods before queues build

---

### Tab 5: Risk Monitor

**What it is:** Real-time dashboard showing which skills (departments) are at risk of not meeting SLA goals.

**How to use:**
1. Click the **Risk** tab
2. View skill cards colour-coded by health:
   - **Green:** On target, healthy
   - **Yellow:** Watch — trending toward risk
   - **Red:** At risk — immediate attention needed
3. For at-risk skills, scroll down to **Smart Overflow** recommendations
4. See exactly:
   - Which proficient agents to move
   - Their AHT scores and proficiency ratings
   - Predicted queue depth reduction after the move

**What makes it powerful:** It doesn't just flag problems — it prescribes the exact fix with predicted impact.

**When to use:**
- Throughout the day during busy periods
- Whenever you see queues building
- Before and after making staffing changes

---

### Tab 6: Coaching

**What it is:** Identifies agents who need coaching and tells you exactly which skills they're struggling with.

**How to use:**
1. Click the **Coaching** tab
2. View the agent list sorted by coaching priority
3. For each agent, see:
   - **Lagging Skills:** Which skills they're slow on
   - **Gap Ratio:** How much slower than team average (e.g., 3.2x = 3.2 times slower)
   - **Contacts Handled:** Volume context
4. Use the recommendations to plan targeted coaching sessions

**Key concept — Gap Ratio:**
- 1.0x = performing at team average
- 2.0x = twice as slow as team average
- 3.0x+ = significantly below average, priority coaching needed

**When to use:**
- Planning weekly coaching sessions
- Performance reviews
- Identifying training needs for specific skills
- Measuring coaching effectiveness over time

---

### Tab 7: Burnout

**What it is:** Detects agents at risk of burnout before they quit or go on sick leave.

**How to use:**
1. Click the **Burnout** tab
2. View agents sorted by risk score (highest first)
3. Risk levels:
   - **High (60-100):** Urgent intervention needed
   - **Medium (35-59):** Monitor closely, schedule check-in
   - **Low (0-34):** Standard monitoring
4. For high-risk agents, check warning signals:
   - Rising handle times (stress indicator)
   - High refusal rates (frustration indicator)
   - Volume drops (disengagement indicator)
   - Excessive overtime (fatigue indicator)
5. Follow the AI recommendation for each agent

**When to use:**
- Weekly wellness checks
- Preventing agent turnover (each departure costs thousands)
- Planning workload redistribution
- Early intervention before performance collapses

---

### Tab 8: Anomaly

**What it is:** Automatically detects unusual patterns or sudden changes in metrics that might indicate problems.

**How to use:**
1. Click the **Anomaly** tab
2. View detected anomalies sorted by severity:
   - **Critical (z-score 3+):** Very unusual, immediate attention
   - **Warning (z-score 2-3):** Notable deviation, investigate
3. For each anomaly, see:
   - **Entity:** Which skill or agent is affected
   - **Current Value** vs **Baseline** (21-day average)
   - **Z-Score:** How many standard deviations from normal
   - **Deviation %:** How far off from expected
   - **Suggested Action:** What to do about it

**Key concept — Z-Score:**
- 2.0 = unusual (happens ~5% of the time normally)
- 3.0 = very unusual (happens ~0.3% of the time)
- 4.0+ = extremely unusual, almost certainly a real issue

**When to use:**
- Catching problems as they start (before they cascade)
- Finding hidden issues you didn't know to look for
- Emergency detection and rapid response
- Validating whether a suspected problem is statistically real

---

### Tab 9: Simulator

**What it is:** A "what-if" tool that lets you test staffing changes mathematically before making them in real life.

**How to use:**
1. Click the **Simulator** tab
2. View **Current State:** agents per skill, current SLA percentage
3. Enter your scenario:
   - Select source skill (move agents FROM)
   - Select target skill (move agents TO)
   - Enter number of agents to move
4. Click **Simulate**
5. View predicted results:
   - SLA before and after for both skills
   - Whether the change is recommended or not
   - Net impact assessment

**Example:** "What if I move 3 agents from Billing to Technical Support?"
- Billing: 92% SLA → 85% SLA (acceptable drop)
- Technical: 65% SLA → 88% SLA (significant improvement)
- Verdict: Safe move, recommended

**When to use:**
- Before making any staffing changes
- Testing multiple scenarios to find optimal configuration
- Understanding trade-offs between skills
- Justifying staffing decisions with data

---

### Tab 10: Shrinkage

**What it is:** Identifies agents who are spending excessive time not handling contacts and calculates the cost impact.

**How to use:**
1. Click the **Shrinkage** tab
2. View agents sorted by shrinkage rate (highest first)
3. For each flagged agent, see:
   - **Shrinkage Rate:** Percentage of paid time NOT on contacts
   - **Level:** Normal / High / Excessive
   - **Cost Impact:** Dollar amount per agent per week in wasted labour
   - **Flags:** Long breaks, excessive training time, low utilization
4. Normal shrinkage is ~30% (breaks, training, admin). Above 50% = needs review.

**When to use:**
- Reducing labour costs
- Improving agent productivity
- Finding scheduling inefficiencies
- Accountability conversations with evidence

---

### Tab 11: Deflection

**What it is:** Finds contacts that could be handled by automation (IVR, chatbot, self-service portal) instead of live agents.

**How to use:**
1. Click the **Deflection** tab
2. View automation opportunities ranked by savings potential
3. For each opportunity, see:
   - **Contact Type:** What kind of contacts (e.g., password resets)
   - **Volume:** How many contacts per period
   - **Avg Handle Time:** How long agents spend on these
   - **Deflection Score (0-100):** How automatable it is
   - **Projected Savings:** Monthly and annual dollar figures
4. High-score, high-volume items are your best automation targets

**When to use:**
- Planning automation roadmap
- Building business cases for chatbot/IVR investment
- Reducing overall contact volume
- Quarterly strategic planning

---

### Tab 12: Agent View

**What it is:** A performance dashboard showing detailed metrics for all individual agents.

**How to use:**
1. Click the **Agents** tab
2. View the team summary: total agents, team average AHT, team SLA
3. Browse individual agents with:
   - **Performance Rating:** Good / Average / Critical
   - **Contacts Handled:** Volume
   - **Handle Time:** Total time per contact
   - **Talk Time:** Actual conversation time
   - **Hold Time:** Customer on hold
   - **After-Call Work (ACW):** Post-contact administration
   - **SLA:** Individual service level
4. Sort and filter to find outliers

**When to use:**
- Performance reviews and comparisons
- Finding top performers to mentor others
- Identifying agents needing support
- Team-level metrics reporting

---

## Recommended Daily Workflow

| Time of Day | What to Do | Tab to Use |
|-------------|-----------|------------|
| Start of day | Check AI briefing for priorities | Briefing |
| Morning | Review which skills are at risk | Risk Monitor |
| Throughout day | Watch for volume surges | Forecast |
| When problems arise | Ask AI to investigate | RCA Chat |
| Before any staffing change | Test it mathematically | Simulator |
| Weekly | Plan coaching sessions | Coaching |
| Weekly | Check agent wellness | Burnout |
| Monthly | Report business value | ROI |
| When alerts fire | Investigate anomalies | Anomaly |
| Quarterly | Plan automation roadmap | Deflection + Shrinkage |

**Tips for best results:**
1. Check Briefing first thing every morning — it sets your priorities
2. Keep Risk Monitor open during busy periods — catch problems early
3. Always use Simulator before making staffing changes — test safely
4. Ask RCA Chat specific questions — the more specific, the better the answer
5. Use Forecast to plan breaks — schedule them during predicted slow periods
6. Review Coaching weekly for consistent improvement
7. Check Burnout monthly — preventing one departure saves thousands

---

## API Reference

All endpoints are REST (GET/POST) and return JSON. All accept an optional `X-Tenant-ID` header for multi-tenant isolation.

### Endpoints

| Endpoint | Method | Description | Typical Response Time |
|----------|--------|-------------|----------------------|
| `/` | GET | Web UI (tabbed interface) | Instant |
| `/risk/overflow/recommendations` | GET | Smart overflow recommendations | 2-4s |
| `/forecast/demand` | GET | 4-hour volume forecast | 1-2s |
| `/burnout/risk` | GET | Agent burnout risk scores | 2-3s |
| `/anomaly/detect` | GET | Real-time anomaly detection | 3-5s |
| `/simulator/current-state` | GET | Current staffing state | ~1s |
| `/simulator/simulate` | POST | What-if staffing simulation | ~1s |
| `/shrinkage/analysis` | GET | Idle time analysis | ~2s |
| `/deflection/opportunities` | GET | Deflection opportunities | ~2s |
| `/roi/summary` | GET | ROI dashboard | 2-3s |
| `/briefing/today` | GET | AI daily briefing | 3-6s |

### Example API Calls

```bash
# Get ROI summary
curl http://localhost:8080/roi/summary

# Get forecast
curl http://localhost:8080/forecast/demand

# Get burnout risk scores
curl http://localhost:8080/burnout/risk

# Get anomalies
curl http://localhost:8080/anomaly/detect

# Get daily briefing
curl http://localhost:8080/briefing/today

# Simulate moving 3 agents
curl -X POST http://localhost:8080/simulator/simulate \
  -H "Content-Type: application/json" \
  -d '{
    "targetAnswerTime": 30,
    "changes": [
      {"skillNo": 1042, "agentDelta": -3},
      {"skillNo": 1078, "agentDelta": 3}
    ]
  }'
```

### Error Handling

- If Snowflake is unavailable → returns realistic mock data (no errors to clients)
- If Bedrock is unavailable → returns template-based responses
- No endpoint should ever return 500 to end users

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│              InsightIQ Frontend (HTML/JS SPA)             │
│        12 Tabs · Real-time Charts · Responsive UI        │
└────────────────────────────┬─────────────────────────────┘
                             │ REST API (JSON)
┌────────────────────────────┴─────────────────────────────┐
│               Spring Boot 3.3 Application                 │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  9 AI Modules (Controller + Service per module)     │ │
│  │  Each module: REST endpoint → query → analyze →     │ │
│  │  optional LLM enrichment → JSON response            │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  Shared Infrastructure                              │ │
│  │  • TenantContext — multi-tenant isolation           │ │
│  │  • SnowflakeExecutor — read-only query layer       │ │
│  │  • BedrockHelper — LLM prompt + response mgmt      │ │
│  │  • GlobalExceptionHandler — graceful fallbacks      │ │
│  └─────────────────────────────────────────────────────┘ │
└──────────┬────────────────────────────────┬──────────────┘
           │                                │
  ┌────────▼────────┐            ┌─────────▼─────────┐
  │  Snowflake DB   │            │   AWS Bedrock     │
  │  (Read-only)    │            │   Claude LLM      │
  │  JDBC + Views   │            │   Converse API    │
  └─────────────────┘            └───────────────────┘
```

### Data Flow

1. User interacts with frontend (clicks tab, asks question)
2. Frontend calls REST API endpoint
3. TenantContext resolves multi-tenant isolation from headers
4. SnowflakeExecutor runs tenant-scoped SQL queries (read-only)
5. Module-specific analytics logic processes the data
6. BedrockHelper enriches with AI-generated insights (optional)
7. Structured JSON response returned to frontend
8. Frontend renders charts, tables, and recommendations
9. If any external system is down → graceful fallback to mock data

---

## Project Structure

```
agentic-rca-sample/
├── src/
│   ├── main/
│   │   ├── java/com/nice/agentic/
│   │   │   ├── AgenticRcaApplication.java     # Entry point
│   │   │   ├── TenantContext.java             # Multi-tenant isolation
│   │   │   ├── BedrockConfig.java             # AWS client configuration
│   │   │   ├── BedrockHelper.java             # LLM orchestration
│   │   │   ├── GlobalExceptionHandler.java    # Error handling
│   │   │   ├── anomaly/                        # Anomaly detection module
│   │   │   ├── briefing/                       # AI daily briefing module
│   │   │   ├── burnout/                        # Burnout risk scoring module
│   │   │   ├── deflection/                     # Contact deflection module
│   │   │   ├── forecast/                       # Demand forecasting module
│   │   │   ├── query/                          # SnowflakeExecutor
│   │   │   ├── risk/                           # Smart overflow module
│   │   │   ├── roi/                            # ROI dashboard module
│   │   │   ├── shrinkage/                      # Shrinkage analysis module
│   │   │   ├── simulator/                      # Erlang-C simulator module
│   │   │   └── tools/                          # RCA agent tools
│   │   └── resources/
│   │       ├── static/index.html              # Frontend UI (all-in-one)
│   │       ├── application.yaml               # Default config
│   │       ├── application-dev.yaml           # Dev config (gitignored)
│   │       └── prompts/                        # LLM system prompts
│   └── test/                                   # Unit & integration tests
├── docs/
│   ├── auto-demo/                              # Automated video generator
│   ├── DEMO_3MIN.md                           # 3-minute demo script
│   └── VIDEO_RECORDING_GUIDE.md               # Recording instructions
├── pom.xml                                     # Maven dependencies
├── run-dev.sh                                  # Convenience run script
└── README.md                                   # This file
```

---

## Configuration

### Main Config (`application.yaml`)

```yaml
server:
  port: 8080

agentic:
  bedrock:
    model-id: us.anthropic.claude-sonnet-4-5-20250929-v1:0
    region: us-west-2
    max-tokens: 4096
    temperature: 0.3
```

### Environment Variables (Production)

| Variable | Required | Description |
|----------|----------|-------------|
| `SNOWFLAKE_JDBC_URL` | Optional | Snowflake JDBC connection string |
| `SNOWFLAKE_USERNAME` | Optional | Snowflake service account |
| `SNOWFLAKE_PASSWORD` | Optional | Snowflake password |
| `AWS_REGION` | Yes | AWS region (default: us-west-2) |
| `SERVER_PORT` | No | Server port (default: 8080) |

### Multi-Tenant Support

- Tenant ID extracted from `X-Tenant-ID` header or `tenantId` query parameter
- All database queries automatically scoped by tenant
- Each tenant sees only their own data

---

## Key Algorithms

### Burnout Risk Scoring (0-100 points)

| Factor | Points | Threshold |
|--------|--------|-----------|
| AHT Trend | 0-30 | +20% WoW = 30pts, +10% = 20pts, +5% = 10pts |
| Refusal Rate | 0-25 | >15% = 25pts, >10% = 20pts, >5% = 10pts |
| Volume Drop | 0-25 | -30% = 25pts, -20% = 15pts, -10% = 10pts |
| Consistency (CV) | 0-20 | CV>0.4 = 20pts, CV>0.3 = 15pts |

### Anomaly Detection (Z-Score)

- **Baseline:** 21-day rolling average per metric per entity
- **Z-score formula:** `z = (current_value - mean) / standard_deviation`
- **Severity:** |z| >= 2.0 (warning), |z| >= 3.0 (critical)
- **Dimensions:** volume, AHT, refusals, agent behavior

### Erlang-C Staffing Simulator

- **Traffic intensity:** `A = calls_per_hour × (avg_AHT / 3600)`
- **Wait probability:** Erlang-C formula (iterative calculation)
- **Predicted SLA:** `SLA = 1 - P(wait) × exp(-(agents - A) × (target_time / AHT))`
- Enables mathematical "what-if" staffing decisions

### Contact Deflection Scoring (0-100)

| Factor | Weight | Logic |
|--------|--------|-------|
| Volume | 30% | Higher volume = higher priority |
| Simplicity | 30% | Lower AHT = simpler = more automatable |
| Consistency | 20% | Low AHT variance = repeatable pattern |
| Agent Breadth | 20% | Many agents handle it = standardized process |

---

## Demo Video Generator

An automated tool creates a fully synced screen recording with AI voiceover.

```bash
cd docs/auto-demo
npm install
node generate-demo.js
```

**Output:** `docs/auto-demo/output/final-demo.mp4` (~3.3 minutes, ~15 MB)

See [docs/auto-demo/README.md](docs/auto-demo/README.md) for customization options (voice, zoom, pacing).

---

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| App won't start | Missing Java 17 | Install JDK 17: `brew install openjdk@17` |
| "Snowflake is not configured" | No dev profile | Run with: `mvn spring-boot:run -Dspring-boot.run.profiles=dev` |
| Briefing shows error | Bedrock not accessible | Check AWS credentials: `aws sts get-caller-identity` |
| Tabs show "Loading..." | Backend not responding | Ensure app is running on port 8080 |
| Mock data shown | Snowflake unreachable | Check `application-dev.yaml` credentials |
| Port 8080 in use | Another process | Kill it: `lsof -ti:8080 \| xargs kill` or change port |
| Build fails | Dependencies missing | Run: `mvn clean install -U` |
| Slow responses (>10s) | Snowflake cold start | Wait for warehouse auto-resume, retry |
| RCA Chat no response | Bedrock timeout | Check AWS region, model ID, and permissions |

---

## Design Decisions

| Decision | Why |
|----------|-----|
| **Read-only Snowflake** | Zero data corruption risk, simplified security audit |
| **Mock data fallback** | App always works — during outages, for local dev, for demos |
| **Erlang-C for staffing** | Industry-standard WFM model, mathematically rigorous |
| **Z-score for anomalies** | Statistically sound, adaptive to tenant baselines, interpretable |
| **Single HTML frontend** | Zero build step, instant deployment, no framework dependency |
| **Per-module packages** | Independent development, clear ownership, easy to add new modules |
| **Multi-tenant isolation** | Enterprise-ready from day one, tenant data never leaks |
| **Graceful degradation** | No module depends on another — partial failures don't cascade |

---

## Contributing

Internal contributions welcome via pull requests.

**Code standards:**
- Google Java Style Guide
- Maximum line length: 120 characters
- Each module is self-contained in its own package
- Controllers return `Map<String, Object>` for JSON flexibility
- All endpoints must handle Snowflake-unavailable gracefully

---

## License

Internal use only — NICE Ltd.

---

**Built for Sparkathon | Babelfish Team | Agentic Platform**
