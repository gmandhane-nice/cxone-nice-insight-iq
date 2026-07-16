# Agentic Decision Intelligence - Demo Script

**Duration:** 12-15 minutes  
**Audience:** Senior Leadership / Sparkathon Judges  
**App URL:** http://localhost:8080  

---

## OPENING (30 seconds)

**[Screen: App landing page with ROI tab visible]**

> "Today I'm presenting Agentic Decision Intelligence — an AI-powered platform that gives contact center supervisors real-time, actionable insights across 9 analytics modules. Unlike existing Copilot which answers individual questions, this system proactively identifies problems, predicts demand, and quantifies business value — all powered by Claude on Bedrock connected to live Snowflake production data."

---

## MODULE 1: ROI Dashboard (1.5 min)

**[Click ROI tab]**

> "Let's start with the bottom line. The ROI dashboard shows this platform saves an estimated **$62 million annually** across our tenant base."

**Talking Points:**
- Point to the large savings number at the top
- Walk through each breakdown card (Coaching, Overflow, Deflection, Shrinkage, Attrition Prevention)
- Highlight "vs. Baseline" comparison section
- Emphasize: "These aren't hypothetical — this is calculated from real production data: 3,776 active agents, 301K contacts in the last 7 days"

**Key Differentiator:**
> "Existing Copilot can answer 'what is our AHT?' — this system proactively tells you 'here's how much money you're leaving on the table and exactly where.'"

---

## MODULE 2: AI Daily Briefing (1.5 min)

**[Click Briefing tab → Click "Generate Briefing"]**

> "Every morning, supervisors get an AI-generated briefing — like a morning newspaper for their contact center."

**Wait for briefing to load (5-10 seconds), then walk through:**

- **Headline**: AI-generated summary of the most important thing happening
- **Priorities**: Color-coded by urgency (critical/high/medium) — point to specific numbers
- **Wins**: Positive trends the team should celebrate
- **Risks**: Things that need attention before they become problems
- **Top Recommendation**: One concrete action to take right now

**Key Differentiator:**
> "This isn't a static report — it's generated fresh by Claude analyzing yesterday's 41,000+ contacts, identifying patterns a human would take hours to spot."

---

## MODULE 3: Demand Forecast (1 min)

**[Click Forecast tab]**

> "The forecast module uses 4 weeks of historical patterns to predict contact volume for the next 4 hours."

**Walk through:**
- KPI cards: Current volume, peak hour, staffing gap, surge alerts
- Insights section (historical pattern recognition)
- Table: Hour-by-hour prediction with staffing sufficiency indicators
- Point out SURGE alerts and CRITICAL staffing warnings

**Key Differentiator:**
> "Supervisors see predicted surges BEFORE they happen, so they can pre-position agents instead of reacting after queues are already building."

---

## MODULE 4: Risk Monitor (1.5 min)

**[Click Risk Monitor tab]**

> "The Risk Monitor shows real-time SLA health across all skills, with AI-powered overflow recommendations."

**Walk through:**
- Risk table with SLA bars (current + forecasted)
- Color-coded risk levels (at-risk, warning, healthy)
- Smart Overflow section: Shows specific agents who are proficient and available to help

**Key Differentiator:**
> "It doesn't just tell you there's a problem — it tells you EXACTLY which agents to move, their proficiency scores, and the predicted queue reduction."

---

## MODULE 5: Coaching - Skill Gap Analysis (1 min)

**[Click Coaching tab]**

> "The coaching module identifies agents performing below average and shows exactly which skills they need help with."

**Walk through:**
- KPI cards: Agents needing coaching, skills with gaps, avg gap ratio
- Table: Agent names, color-coded skill gap pills (red = 3x+ worse, orange = 2x+)
- Recommendations column
- "Plan Training" action button

**Key Differentiator:**
> "Instead of generic coaching, supervisors get precision targeting — 'Sarah is 3.2x slower on billing, but fine on everything else. Focus coaching there.'"

---

## MODULE 6: Burnout Risk (1 min)

**[Click Burnout tab]**

> "Agent attrition costs $8,000 per replacement. This module detects burnout risk BEFORE agents quit."

**Walk through:**
- Summary: High/Medium/Low risk counts
- Individual agents with risk scores (0-100)
- Key signals: AHT trends, refusal rates, volume drops, consistency changes
- Recommendations: "Reduce workload", "Schedule wellness check"

**Key Differentiator:**
> "This saves real money. If we prevent just 30% of at-risk agents from leaving, that's significant savings in hiring and training costs."

---

## MODULE 7: Anomaly Detection (45 sec)

**[Click Anomaly tab → Click Refresh]**

> "Real-time statistical anomaly detection using Z-score analysis. It catches problems humans wouldn't notice until it's too late."

**Walk through:**
- Critical vs Warning classification
- Z-scores (2.0+ = warning, 3.0+ = critical)
- Current value vs baseline comparison
- Suggested actions

---

## MODULE 8: What-If Simulator (1 min)

**[Click Simulator tab → Click "Load Current State"]**

> "Before making any staffing change, supervisors can simulate the impact using Erlang-C queuing theory."

**Demo the simulation:**
1. Select "Move 3 agents from [overstaffed skill] to [understaffed skill]"
2. Click "Simulate"
3. Show before/after SLA comparison
4. Point out the verdict and recommendation

**Key Differentiator:**
> "No more guessing. Test your decisions mathematically before executing them."

---

## MODULE 9: Contact Deflection (1 min)

**[Click Deflection tab]**

> "This module identifies high-volume, low-complexity contacts that could be automated — and quantifies the savings."

**Walk through:**
- Summary: 15 opportunities, $4.45M/year potential savings
- Opportunity cards: Skill name, deflection score, volume, automation type (IVR/Chatbot)
- Rationale explaining why each is a good candidate

---

## MODULE 10: Shrinkage Analysis (45 sec)

**[Click Shrinkage tab]**

> "Identifies agents with excessive idle time and calculates the cost impact."

**Walk through:**
- Team summary: Average shrinkage rate, weekly cost
- Individual agents with flags (long breaks, low utilization)
- Cost impact per agent

---

## MODULE 11: RCA Chat (1.5 min)

**[Click RCA Chat tab]**

> "Finally, the conversational AI interface — ask any question in natural language."

**Live Demo - Type one of these:**
- "Why is PerfUser_0011 unavailable for long time?"
- "Which skills have the highest refusal rate?"
- "Show me agents with more than 1000 contacts this week"

**Wait for response, then walk through:**
- Multi-step investigation (planning → investigating → reasoning → recommending)
- Structured results with charts and KPI cards
- "Recommended Next Steps" buttons for follow-up questions

**Key Differentiator:**
> "This isn't a simple chatbot. It's a multi-agent system — Claude orchestrates data gathering, statistical analysis, and recommendation generation in a single flow."

---

## CLOSING (30 seconds)

> "To summarize: 9 AI-powered modules, all connected to live production Snowflake data, providing proactive intelligence rather than reactive answers. This isn't another chatbot — it's a decision intelligence platform that pays for itself through measurable cost savings."

**Key Numbers to Repeat:**
- $62M estimated annual savings
- 41,000+ contacts analyzed daily
- 2,700+ agents monitored
- 15 deflection opportunities identified
- Real-time anomaly detection

---

## BACKUP Q&A ANSWERS

**Q: How is this different from existing Copilot?**
> "Copilot answers questions reactively. This system proactively surfaces problems, predicts demand, quantifies ROI, and recommends specific actions — without being asked."

**Q: Is this using real data?**
> "Yes — all 9 modules connect to Snowflake production data via AGENT_CONTACT_FACT_VIEW_V011. No mock data in this demo."

**Q: What AI model powers this?**
> "Claude 3.5 Sonnet on Amazon Bedrock, with custom prompt engineering for contact center domain expertise."

**Q: How do you calculate the ROI numbers?**
> "Industry benchmarks: $25/hr agent cost, $0.50/contact processing, $8,000 attrition replacement cost, applied to actual contact volumes and agent counts from Snowflake."

**Q: Can this scale to other tenants?**
> "Yes — it's tenant-aware. The dropdown at the top switches between tenants. All queries are scoped by _TENANT_ID."

---

## DEMO ENVIRONMENT CHECKLIST

Before recording/presenting:
- [ ] App running: `cd /Users/Nimish.Kasaudhan/agentic-rca-sample && mvn spring-boot:run`
- [ ] Verify Snowflake connection: Hit http://localhost:8080/health
- [ ] Pre-load ROI tab (takes 3-5 sec first time)
- [ ] Pre-load Briefing (takes 10-15 sec due to LLM call)
- [ ] Browser: Chrome, dark mode, full screen (Cmd+Shift+F)
- [ ] Font size: 110-120% zoom for visibility
- [ ] Close all other tabs/notifications
