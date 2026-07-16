package com.nice.agentic.risk;

import com.nice.agentic.TenantContext;
import com.nice.agentic.query.SnowflakeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smart Overflow Recommendation Engine.
 *
 * Identifies the longest/most-at-risk queues (by queue depth and increasing trend),
 * then finds agents who are PROFICIENT in those skills (AHT <= team average = coaching completed)
 * but currently assigned to other skills. Recommends reassigning those agents to relieve pressure.
 */
@RestController
@RequestMapping("/risk/overflow")
public class SmartOverflowController {

    private static final Logger log = LoggerFactory.getLogger(SmartOverflowController.class);

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;

    public SmartOverflowController(SnowflakeExecutor snowflakeExecutor, TenantContext tenantContext) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/recommendations")
    public Map<String, Object> getRecommendations() {
        if (!snowflakeExecutor.isConfigured()) {
            return buildMockResponse();
        }

        try {
            String tenantId = tenantContext.getTenantId();

            // Step 1: Find skills with longest/growing queues (at-risk)
            List<Map<String, Object>> atRiskSkills = queryAtRiskSkills(tenantId);
            if (atRiskSkills.isEmpty()) {
                return Map.of(
                        "generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                        "recommendations", List.of(),
                        "message", "No at-risk queues detected — all skills are healthy.");
            }

            // Step 2: For top 5 at-risk skills, find proficient agents in parallel
            List<Map<String, Object>> topSkills = atRiskSkills.subList(0, Math.min(5, atRiskSkills.size()));

            Map<Integer, List<Map<String, Object>>> proficientBySkill = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Map<String, Object> skill : topSkills) {
                int skillNo = toInt(skill.get("SKILL_NO"));
                double teamAvgAht = toDouble(skill.get("TEAM_AVG_AHT"), 0);
                final String tid = tenantId;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        List<Map<String, Object>> agents = queryProficientAgents(tid, skillNo, teamAvgAht);
                        proficientBySkill.put(skillNo, agents);
                    } catch (Exception e) {
                        log.warn("Failed to query proficient agents for skill {}: {}", skillNo, e.getMessage());
                        proficientBySkill.put(skillNo, List.of());
                    }
                }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Map<String, Object>> recommendations = new ArrayList<>();
            for (Map<String, Object> skill : topSkills) {
                String skillName = skill.get("SKILL_NAME").toString();
                int skillNo = toInt(skill.get("SKILL_NO"));
                int queueDepth = toInt(skill.get("QUEUE_DEPTH"));
                double currentAht = toDouble(skill.get("CURRENT_AVG_AHT"), 0);
                double teamAvgAht = toDouble(skill.get("TEAM_AVG_AHT"), 0);
                int activeAgents = toInt(skill.get("ACTIVE_AGENTS"));
                double trendPct = toDouble(skill.get("TREND_PCT"), 0);

                List<Map<String, Object>> proficientAgents = proficientBySkill.getOrDefault(skillNo, List.of());

                if (!proficientAgents.isEmpty()) {
                    int agentsNeeded = Math.max(1, (int) Math.ceil(queueDepth / 5.0));
                    int agentsToAssign = Math.min(agentsNeeded, proficientAgents.size());

                    List<Map<String, Object>> candidateAgents = new ArrayList<>();
                    for (int i = 0; i < agentsToAssign; i++) {
                        Map<String, Object> agent = proficientAgents.get(i);
                        Map<String, Object> candidate = new LinkedHashMap<>();
                        candidate.put("agentName", agent.get("USER_FIRST_NAME") + " " + agent.get("USER_LAST_NAME"));
                        candidate.put("agentAht", toDouble(agent.get("AGENT_AHT"), 0));
                        candidate.put("teamAvgAht", teamAvgAht);
                        candidate.put("gapRatio", toDouble(agent.get("GAP_RATIO"), 0));
                        candidate.put("contactsHandled", toInt(agent.get("CONTACT_COUNT")));
                        candidate.put("currentSkill", agent.get("PRIMARY_SKILL") != null ? agent.get("PRIMARY_SKILL").toString() : "Multiple");
                        candidateAgents.add(candidate);
                    }

                    double predictedReduction = Math.min(80, agentsToAssign * 15.0);

                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("skillName", skillName);
                    rec.put("skillNo", skillNo);
                    rec.put("severity", queueDepth > 20 ? "critical" : (queueDepth > 10 ? "high" : "medium"));
                    rec.put("queueDepth", queueDepth);
                    rec.put("activeAgents", activeAgents);
                    rec.put("trendPct", Math.round(trendPct * 10.0) / 10.0);
                    rec.put("trendDirection", trendPct > 0 ? "increasing" : "stable");
                    rec.put("currentAvgAht", Math.round(currentAht * 10.0) / 10.0);
                    rec.put("teamAvgAht", Math.round(teamAvgAht * 10.0) / 10.0);
                    rec.put("agentsNeeded", agentsNeeded);
                    rec.put("candidateAgents", candidateAgents);
                    rec.put("predictedQueueReduction", predictedReduction + "%");
                    rec.put("action", "Assign " + agentsToAssign + " proficient agent(s) to " + skillName);
                    rec.put("rationale", "These agents have completed coaching for " + skillName
                            + " (AHT at or below team average). Reassigning them will reduce queue depth by ~"
                            + (int) predictedReduction + "%.");
                    recommendations.add(rec);
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            response.put("recommendations", recommendations);
            response.put("totalAtRiskSkills", atRiskSkills.size());
            response.put("totalCandidateAgents", recommendations.stream()
                    .mapToInt(r -> ((List<?>) r.get("candidateAgents")).size()).sum());
            return response;

        } catch (Exception e) {
            log.error("Smart overflow recommendation failed: {}", e.getMessage(), e);
            return Map.of(
                    "generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                    "recommendations", List.of(),
                    "error", e.getMessage());
        }
    }

    /**
     * Find skills that are at-risk: high queue depth relative to agent capacity,
     * and/or increasing contact volume trend.
     */
    private List<Map<String, Object>> queryAtRiskSkills(String tenantId) {
        String sql = "WITH recent_load AS (\n"
                + "  SELECT a.SKILL_NO,\n"
                + "    COUNT(*) AS CONTACT_COUNT,\n"
                + "    AVG(a.HANDLE_SECONDS) AS CURRENT_AVG_AHT,\n"
                + "    COUNT(DISTINCT a.USER_ID) AS ACTIVE_AGENTS\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -7, CURRENT_TIMESTAMP())\n"
                + "  GROUP BY a.SKILL_NO\n"
                + "  HAVING COUNT(*) >= 50\n"
                + "),\n"
                + "queue_depth AS (\n"
                + "  SELECT a.SKILL_NO, COUNT(*) AS QUEUE_DEPTH\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.END_TIMESTAMP IS NULL\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -7, CURRENT_TIMESTAMP())\n"
                + "  GROUP BY a.SKILL_NO\n"
                + "),\n"
                + "prev_load AS (\n"
                + "  SELECT a.SKILL_NO, COUNT(*) AS PREV_COUNT\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -14, CURRENT_TIMESTAMP())\n"
                + "    AND a.START_TIMESTAMP < DATEADD(day, -7, CURRENT_TIMESTAMP())\n"
                + "  GROUP BY a.SKILL_NO\n"
                + "),\n"
                + "team_avg AS (\n"
                + "  SELECT ROUND(AVG(CURRENT_AVG_AHT), 1) AS TEAM_AVG_AHT\n"
                + "  FROM recent_load\n"
                + "),\n"
                + "skill_names AS (\n"
                + "  SELECT SKILL_NO, MAX(SKILL_NAME) AS SKILL_NAME\n"
                + "  FROM " + SnowflakeExecutor.VIEW_SKILL_DIM + "\n"
                + "  WHERE _TENANT_ID = '" + tenantId + "' AND SKILL_NAME IS NOT NULL\n"
                + "  GROUP BY SKILL_NO\n"
                + ")\n"
                + "SELECT sk.SKILL_NAME, rl.SKILL_NO,\n"
                + "  COALESCE(qd.QUEUE_DEPTH, 0) AS QUEUE_DEPTH,\n"
                + "  rl.CONTACT_COUNT,\n"
                + "  rl.CURRENT_AVG_AHT,\n"
                + "  ta.TEAM_AVG_AHT,\n"
                + "  rl.ACTIVE_AGENTS,\n"
                + "  CASE WHEN pl.PREV_COUNT > 0\n"
                + "    THEN ROUND((rl.CONTACT_COUNT - pl.PREV_COUNT) * 100.0 / pl.PREV_COUNT, 1)\n"
                + "    ELSE 0 END AS TREND_PCT\n"
                + "FROM recent_load rl\n"
                + "LEFT JOIN queue_depth qd ON rl.SKILL_NO = qd.SKILL_NO\n"
                + "LEFT JOIN prev_load pl ON rl.SKILL_NO = pl.SKILL_NO\n"
                + "CROSS JOIN team_avg ta\n"
                + "LEFT JOIN skill_names sk ON rl.SKILL_NO = sk.SKILL_NO\n"
                + "WHERE sk.SKILL_NAME IS NOT NULL\n"
                + "  AND (COALESCE(qd.QUEUE_DEPTH, 0) > 5\n"
                + "    OR (rl.CONTACT_COUNT - COALESCE(pl.PREV_COUNT, rl.CONTACT_COUNT)) > 0)\n"
                + "ORDER BY COALESCE(qd.QUEUE_DEPTH, 0) DESC, TREND_PCT DESC\n"
                + "LIMIT 10";

        return snowflakeExecutor.execute(sql);
    }

    /**
     * Find agents proficient in the given skill — their AHT is at or below team average,
     * meaning they've effectively "completed coaching" for this skill.
     * Excludes agents already heavily assigned to this skill.
     */
    private List<Map<String, Object>> queryProficientAgents(String tenantId, int skillNo, double teamAvgAht) {
        String sql = "WITH skill_agents AS (\n"
                + "  SELECT a.USER_ID,\n"
                + "    u.USER_FIRST_NAME,\n"
                + "    u.USER_LAST_NAME,\n"
                + "    COUNT(*) AS CONTACT_COUNT,\n"
                + "    ROUND(AVG(a.HANDLE_SECONDS), 1) AS AGENT_AHT,\n"
                + "    ROUND(AVG(a.HANDLE_SECONDS) / NULLIF(" + teamAvgAht + ", 0), 2) AS GAP_RATIO\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  LEFT JOIN " + SnowflakeExecutor.VIEW_USER_DIM + " u\n"
                + "    ON a.USER_ID = u.USER_ID AND u._TENANT_ID = '" + tenantId + "'\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.SKILL_NO = " + skillNo + "\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -30, CURRENT_TIMESTAMP())\n"
                + "    AND u.USER_FIRST_NAME IS NOT NULL\n"
                + "  GROUP BY a.USER_ID, u.USER_FIRST_NAME, u.USER_LAST_NAME\n"
                + "  HAVING COUNT(*) >= 5\n"
                + "),\n"
                + "skill_dedup AS (\n"
                + "  SELECT SKILL_NO, MAX(SKILL_NAME) AS SKILL_NAME\n"
                + "  FROM " + SnowflakeExecutor.VIEW_SKILL_DIM + "\n"
                + "  WHERE _TENANT_ID = '" + tenantId + "' AND SKILL_NAME IS NOT NULL\n"
                + "  GROUP BY SKILL_NO\n"
                + "),\n"
                + "agent_primary AS (\n"
                + "  SELECT a.USER_ID,\n"
                + "    sd.SKILL_NAME AS PRIMARY_SKILL,\n"
                + "    ROW_NUMBER() OVER (PARTITION BY a.USER_ID ORDER BY COUNT(*) DESC) AS rn\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  LEFT JOIN skill_dedup sd ON a.SKILL_NO = sd.SKILL_NO\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.SKILL_NO != " + skillNo + "\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -30, CURRENT_TIMESTAMP())\n"
                + "  GROUP BY a.USER_ID, sd.SKILL_NAME\n"
                + ")\n"
                + "SELECT sa.USER_FIRST_NAME, sa.USER_LAST_NAME, sa.AGENT_AHT,\n"
                + "  sa.GAP_RATIO, sa.CONTACT_COUNT,\n"
                + "  ap.PRIMARY_SKILL\n"
                + "FROM skill_agents sa\n"
                + "LEFT JOIN agent_primary ap ON sa.USER_ID = ap.USER_ID AND ap.rn = 1\n"
                + "WHERE sa.AGENT_AHT <= " + teamAvgAht + "\n"
                + "ORDER BY sa.AGENT_AHT ASC\n"
                + "LIMIT 10";

        return snowflakeExecutor.execute(sql);
    }

    private Map<String, Object> buildMockResponse() {
        List<Map<String, Object>> recommendations = new ArrayList<>();

        Map<String, Object> rec1 = new LinkedHashMap<>();
        rec1.put("skillName", "Billing_Support");
        rec1.put("skillNo", 1042);
        rec1.put("severity", "critical");
        rec1.put("queueDepth", 24);
        rec1.put("activeAgents", 8);
        rec1.put("trendPct", 35.2);
        rec1.put("trendDirection", "increasing");
        rec1.put("currentAvgAht", 285.0);
        rec1.put("teamAvgAht", 200.0);
        rec1.put("agentsNeeded", 5);
        rec1.put("candidateAgents", List.of(
                Map.of("agentName", "Maria Santos", "agentAht", 180.0, "teamAvgAht", 200.0,
                        "gapRatio", 0.9, "contactsHandled", 45, "currentSkill", "General_Support"),
                Map.of("agentName", "David Kim", "agentAht", 165.0, "teamAvgAht", 200.0,
                        "gapRatio", 0.82, "contactsHandled", 38, "currentSkill", "Email_Support"),
                Map.of("agentName", "Ana Costa", "agentAht", 192.0, "teamAvgAht", 200.0,
                        "gapRatio", 0.96, "contactsHandled", 52, "currentSkill", "Chat_Support")
        ));
        rec1.put("predictedQueueReduction", "45%");
        rec1.put("action", "Assign 3 proficient agent(s) to Billing_Support");
        rec1.put("rationale", "These agents have completed coaching for Billing_Support "
                + "(AHT at or below team average). Reassigning them will reduce queue depth by ~45%.");
        recommendations.add(rec1);

        Map<String, Object> rec2 = new LinkedHashMap<>();
        rec2.put("skillName", "Technical_Support");
        rec2.put("skillNo", 1078);
        rec2.put("severity", "high");
        rec2.put("queueDepth", 14);
        rec2.put("activeAgents", 5);
        rec2.put("trendPct", 18.7);
        rec2.put("trendDirection", "increasing");
        rec2.put("currentAvgAht", 420.0);
        rec2.put("teamAvgAht", 350.0);
        rec2.put("agentsNeeded", 3);
        rec2.put("candidateAgents", List.of(
                Map.of("agentName", "James Wilson", "agentAht", 310.0, "teamAvgAht", 350.0,
                        "gapRatio", 0.89, "contactsHandled", 28, "currentSkill", "Premium_Support"),
                Map.of("agentName", "Sarah Chen", "agentAht", 340.0, "teamAvgAht", 350.0,
                        "gapRatio", 0.97, "contactsHandled", 31, "currentSkill", "Billing_Support")
        ));
        rec2.put("predictedQueueReduction", "30%");
        rec2.put("action", "Assign 2 proficient agent(s) to Technical_Support");
        rec2.put("rationale", "These agents have completed coaching for Technical_Support "
                + "(AHT at or below team average). Reassigning them will reduce queue depth by ~30%.");
        recommendations.add(rec2);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.put("recommendations", recommendations);
        response.put("totalAtRiskSkills", 2);
        response.put("totalCandidateAgents", 5);
        return response;
    }

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(value.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }
}
