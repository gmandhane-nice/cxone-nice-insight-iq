# Sparkathon MVP — Demo Runbook v0

**Total runtime:** ~5 minutes  
**Presenter:** Person E  
**Last verified:** [fill in before demo]

---

## Pre-demo checklist (5 min before going live)

- [ ] SSM tunnels running: `pwsh tunnels.ps1` → select dev → toggle OpenSearch + Valkey
- [ ] OpenSearch reachable: `curl -k https://localhost:6380` → returns cluster info
- [ ] Valkey reachable: `redis-cli -p 6379 PING` → PONG
- [ ] Service running: `curl http://localhost:8080/actuator/health` → 200
- [ ] Frontend running: `http://localhost:4200` loads in browser
- [ ] Bedrock accessible: `aws sts get-caller-identity` returns the federation role
- [ ] All 3 panels visible: Chat, Risk, Agent tabs in Angular app
- [ ] Risk panel shows Banking at-risk (red badge)
- [ ] Pre-recorded backup video loaded and ready to play

---

## Scenario 1 — RCA (2 min)

**Goal:** Show that Claude investigates a metric anomaly using multiple tools and identifies the root cause.

1. Navigate to **Chat** tab in the browser
2. Type: `Why has the Banking queue AHT increased today?`
3. Press **Ask**
4. **What judges see:**
   - Investigation log appears: "→ metric_snapshot", "→ metric_history", "→ agent_performance_slice"
   - Final cards appear showing:
     - **Cause 1 (confidence ~80):** New-hire cohort (0-30 days) — AHT 578s vs 289s baseline
     - **Recommended action:** Review routing assignment for new hires
5. **Talking point:** "Claude called 3 tools, correlated the staffing event at 11:00 with the AHT spike, and identified the exact cohort without any manual query writing."

**Fallback if live fails:** Show the pre-recorded video clip (Scenario1.mp4)

---

## Scenario 2 — Risk (1 min)

**Goal:** Show the proactive risk monitoring panel.

1. Navigate to **Risk** tab
2. **What judges see:**
   - Table with 3 skills: Banking (🔴 at-risk), Collections (🟡 warning), General-Support (🟢 healthy)
   - Banking row: SLA 79%, Queue depth 23, Recommendation: "Overflow 20% to General-Support for 30 min → +5% SLA"
3. **Talking point:** "This panel polls every 10 seconds. The SLA forecast is computed from live queue data plus the ongoing AHT regression."

**Fallback:** Panel shows static snapshot JSON — still demonstrates the UX.

---

## Scenario 3 — Coaching (1 min)

**Goal:** Show the agent coaching nudge appearing in real time.

1. Open a second browser tab, navigate to **Agent** tab (agent AG-042 view)
2. In a terminal: `curl -X POST http://localhost:8080/coach/trigger -H "Content-Type: application/json" -d @src/main/resources/fixtures/coach-trigger-fixture.json`
3. **What judges see:**
   - Nudge card pops up in bottom-right corner within ~5 seconds
   - Text: "Your AHT is trending 22% above your usual. Two of the last three contacts were billing disputes — try the 3-step de-escalation from module B4."
   - Click **Dismiss** — card disappears
4. **Talking point:** "Claude generated this nudge in ~2 seconds using real performance context. It's specific, actionable, and linked to a training module."

**Fallback:** Show the nudge card pre-populated with the fixture payload (static HTML mockup).

---

## Known safe questions (Scenario 1)
These have been tested and reliably produce the expected root cause:
- "Why has the Banking queue AHT increased today?"
- "What is causing the AHT spike in Banking?"
- "Why is AHT elevated in the Banking skill today?"

## Known risky questions (avoid live)
- Anything asking about raw SQL or data schemas
- Questions about skills other than Banking (stub data only covers Banking)
- "What happened yesterday?" (time range outside stub data)

---

## Emergency contacts during demo
- Backend issues: Person A
- Data issues: Person C  
- Frontend issues: Person D
- SSE/coach issues: Person E
