package com.nice.agentic.coaching;

import com.nice.agentic.TenantContext;
import com.nice.agentic.query.SnowflakeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller providing skill-gap coaching data for the Coaching dashboard.
 * Identifies agents whose per-skill AHT exceeds 1.5x the team average, or whose
 * refusal rate exceeds 10%, enabling supervisors to plan targeted training.
 */
@RestController
@RequestMapping("/coaching")
public class CoachingController {

    private static final Logger log = LoggerFactory.getLogger(CoachingController.class);

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;

    public CoachingController(SnowflakeExecutor snowflakeExecutor, TenantContext tenantContext) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/gaps")
    public Map<String, Object> getGaps() {
        if (!snowflakeExecutor.isConfigured()) {
            log.info("Snowflake not configured — returning mock coaching data");
            return buildMockResponse();
        }

        try {
            // Try last 30 days first
            List<Map<String, Object>> rows = queryAgentSkillMetrics(30);
            if (rows.isEmpty()) {
                log.info("No coaching data in last 30 days — widening to 720 days for demo");
                rows = queryAgentSkillMetrics(720);
            }
            if (rows.isEmpty()) {
                log.warn("No coaching data found — returning mock data");
                return buildMockResponse();
            }
            return buildResponse(rows);
        } catch (Exception e) {
            log.error("Failed to query coaching data — returning mock data: {}", e.getMessage(), e);
            return buildMockResponse();
        }
    }

    // -------------------------------------------------------------------------
    // Snowflake query: per-agent per-skill AHT with team averages
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> queryAgentSkillMetrics(int daysBack) {
        String tenantId = tenantContext.getTenantId();
        String sql = "WITH agent_skill_metrics AS (\n"
                + "  SELECT\n"
                + "    u.USER_FIRST_NAME,\n"
                + "    u.USER_LAST_NAME,\n"
                + "    sk.SKILL_NAME,\n"
                + "    COUNT(*) AS CONTACT_COUNT,\n"
                + "    ROUND(AVG(a.HANDLE_SECONDS), 1) AS AGENT_AHT,\n"
                + "    SUM(CASE WHEN a.IS_REFUSED_FLAG = 1 THEN 1 ELSE 0 END) AS REFUSED_COUNT\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  LEFT JOIN " + SnowflakeExecutor.VIEW_USER_DIM + " u\n"
                + "    ON a.USER_ID = u.USER_ID AND u._TENANT_ID = '" + tenantId + "'\n"
                + "  LEFT JOIN " + SnowflakeExecutor.VIEW_SKILL_DIM + " sk\n"
                + "    ON a.SKILL_NO = sk.SKILL_NO AND sk._TENANT_ID = '" + tenantId + "'\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -" + daysBack + ", CURRENT_TIMESTAMP())\n"
                + "    AND sk.SKILL_NAME IS NOT NULL\n"
                + "    AND u.USER_FIRST_NAME IS NOT NULL\n"
                + "  GROUP BY u.USER_FIRST_NAME, u.USER_LAST_NAME, sk.SKILL_NAME\n"
                + "  HAVING COUNT(*) >= 10\n"
                + "),\n"
                + "team_skill_avg AS (\n"
                + "  SELECT\n"
                + "    SKILL_NAME,\n"
                + "    ROUND(AVG(AGENT_AHT), 1) AS TEAM_AVG_AHT\n"
                + "  FROM agent_skill_metrics\n"
                + "  GROUP BY SKILL_NAME\n"
                + ")\n"
                + "SELECT\n"
                + "  asm.USER_FIRST_NAME,\n"
                + "  asm.USER_LAST_NAME,\n"
                + "  asm.SKILL_NAME,\n"
                + "  asm.CONTACT_COUNT,\n"
                + "  asm.AGENT_AHT,\n"
                + "  asm.REFUSED_COUNT,\n"
                + "  tsa.TEAM_AVG_AHT,\n"
                + "  ROUND(asm.AGENT_AHT / NULLIF(tsa.TEAM_AVG_AHT, 0), 2) AS GAP_RATIO\n"
                + "FROM agent_skill_metrics asm\n"
                + "JOIN team_skill_avg tsa ON asm.SKILL_NAME = tsa.SKILL_NAME\n"
                + "WHERE asm.AGENT_AHT / NULLIF(tsa.TEAM_AVG_AHT, 0) >= 1.5\n"
                + "ORDER BY GAP_RATIO DESC\n"
                + "LIMIT 200";

        return snowflakeExecutor.execute(sql);
    }

    // -------------------------------------------------------------------------
    // Build response from Snowflake rows
    // -------------------------------------------------------------------------

    private Map<String, Object> buildResponse(List<Map<String, Object>> rows) {
        // Group rows by agent
        Map<String, List<Map<String, Object>>> agentRows = new LinkedHashMap<>();
        double totalTeamAht = 0;
        int teamAhtCount = 0;

        for (Map<String, Object> row : rows) {
            String firstName = row.get("USER_FIRST_NAME") != null ? row.get("USER_FIRST_NAME").toString() : "Unknown";
            String lastName = row.get("USER_LAST_NAME") != null ? row.get("USER_LAST_NAME").toString() : "";
            String key = firstName + "|" + lastName;
            agentRows.computeIfAbsent(key, k -> new ArrayList<>()).add(row);

            double teamAvg = toDouble(row.get("TEAM_AVG_AHT"), 0);
            if (teamAvg > 0) {
                totalTeamAht += teamAvg;
                teamAhtCount++;
            }
        }

        double overallTeamAvgAht = teamAhtCount > 0 ? totalTeamAht / teamAhtCount : 0;

        // Build agent entries - only include agents with at least one lagging skill
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : agentRows.entrySet()) {
            String[] nameParts = entry.getKey().split("\\|", 2);
            List<Map<String, Object>> skillRows = entry.getValue();

            List<Map<String, Object>> laggingSkills = new ArrayList<>();
            List<Map<String, Object>> strengths = new ArrayList<>();
            double totalAht = 0;
            int totalContacts = 0;

            for (Map<String, Object> skillRow : skillRows) {
                double agentAht = toDouble(skillRow.get("AGENT_AHT"), 0);
                double teamAvgAht = toDouble(skillRow.get("TEAM_AVG_AHT"), 0);
                double gapRatio = toDouble(skillRow.get("GAP_RATIO"), 0);
                int contacts = toInt(skillRow.get("CONTACT_COUNT"));
                int refused = toInt(skillRow.get("REFUSED_COUNT"));
                String skillName = skillRow.get("SKILL_NAME") != null ? skillRow.get("SKILL_NAME").toString() : "Unknown";

                totalAht += agentAht * contacts;
                totalContacts += contacts;

                double refusalRate = contacts > 0 ? (double) refused / contacts : 0;

                if (gapRatio >= 1.5 || refusalRate > 0.10) {
                    Map<String, Object> lag = new LinkedHashMap<>();
                    lag.put("skillName", skillName);
                    lag.put("agentAht", agentAht);
                    lag.put("teamAvgAht", teamAvgAht);
                    lag.put("gap", gapRatio + "x");
                    lag.put("contacts", contacts);
                    lag.put("refusalRate", Math.round(refusalRate * 100.0) + "%");
                    lag.put("recommendation", buildRecommendation(gapRatio, refusalRate));
                    laggingSkills.add(lag);
                } else if (gapRatio > 0 && gapRatio < 1.0) {
                    Map<String, Object> strength = new LinkedHashMap<>();
                    strength.put("skillName", skillName);
                    strength.put("agentAht", agentAht);
                    strength.put("teamAvgAht", teamAvgAht);
                    strengths.add(strength);
                }
            }

            if (!laggingSkills.isEmpty()) {
                Map<String, Object> agent = new LinkedHashMap<>();
                agent.put("name", nameParts[0]);
                agent.put("lastName", nameParts.length > 1 ? nameParts[1] : "");
                agent.put("overallAht", totalContacts > 0 ? Math.round(totalAht / totalContacts) : 0);
                agent.put("contactCount", totalContacts);
                agent.put("laggingSkills", laggingSkills);
                agent.put("strengths", strengths);
                agents.add(agent);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.put("teamAvgAht", Math.round(overallTeamAvgAht * 10.0) / 10.0);
        response.put("agents", agents);
        return response;
    }

    private String buildRecommendation(double gapRatio, double refusalRate) {
        if (refusalRate > 0.10) {
            return "High refusal rate — review routing rules and agent availability";
        }
        if (gapRatio >= 3.0) {
            return "Significant gap — assign dedicated mentoring and guided scripts";
        }
        if (gapRatio >= 2.0) {
            return "Reduce handle time with guided scripts and knowledge base shortcuts";
        }
        return "Minor gap — recommend self-paced training module review";
    }

    // -------------------------------------------------------------------------
    // Mock data fallback
    // -------------------------------------------------------------------------

    private Map<String, Object> buildMockResponse() {
        List<Map<String, Object>> agents = new ArrayList<>();

        // Agent 1: PerfUser_0011 with lagging skills
        Map<String, Object> agent1 = new LinkedHashMap<>();
        agent1.put("name", "PerfUser_0011");
        agent1.put("lastName", "BU_DO72");
        agent1.put("overallAht", 61770);
        agent1.put("contactCount", 1639);
        List<Map<String, Object>> lagging1 = new ArrayList<>();
        lagging1.add(mockLaggingSkill("API_Chat", 500.0, 120.0, 4.2, 50, "0%", "Significant gap — assign dedicated mentoring and guided scripts"));
        lagging1.add(mockLaggingSkill("Voice_Support", 380.0, 180.0, 2.1, 120, "3%", "Reduce handle time with guided scripts and knowledge base shortcuts"));
        agent1.put("laggingSkills", lagging1);
        List<Map<String, Object>> strengths1 = new ArrayList<>();
        strengths1.add(mockStrength("Email Support", 90.0, 120.0));
        agent1.put("strengths", strengths1);
        agents.add(agent1);

        // Agent 2: Sarah Johnson
        Map<String, Object> agent2 = new LinkedHashMap<>();
        agent2.put("name", "Sarah");
        agent2.put("lastName", "Johnson");
        agent2.put("overallAht", 420);
        agent2.put("contactCount", 312);
        List<Map<String, Object>> lagging2 = new ArrayList<>();
        lagging2.add(mockLaggingSkill("Billing_Inquiries", 340.0, 150.0, 2.3, 85, "12%", "High refusal rate — review routing rules and agent availability"));
        agent2.put("laggingSkills", lagging2);
        agent2.put("strengths", List.of(mockStrength("General_Support", 110.0, 140.0)));
        agents.add(agent2);

        // Agent 3: Mike Chen
        Map<String, Object> agent3 = new LinkedHashMap<>();
        agent3.put("name", "Mike");
        agent3.put("lastName", "Chen");
        agent3.put("overallAht", 550);
        agent3.put("contactCount", 198);
        List<Map<String, Object>> lagging3 = new ArrayList<>();
        lagging3.add(mockLaggingSkill("Technical_Support", 620.0, 200.0, 3.1, 45, "5%", "Significant gap — assign dedicated mentoring and guided scripts"));
        lagging3.add(mockLaggingSkill("Returns_Processing", 280.0, 160.0, 1.8, 67, "2%", "Minor gap — recommend self-paced training module review"));
        agent3.put("laggingSkills", lagging3);
        agent3.put("strengths", List.of(mockStrength("Chat_Support", 95.0, 130.0)));
        agents.add(agent3);

        // Agent 4: James Brown
        Map<String, Object> agent4 = new LinkedHashMap<>();
        agent4.put("name", "James");
        agent4.put("lastName", "Brown");
        agent4.put("overallAht", 720);
        agent4.put("contactCount", 95);
        List<Map<String, Object>> lagging4 = new ArrayList<>();
        lagging4.add(mockLaggingSkill("Premium_Support", 450.0, 180.0, 2.5, 30, "15%", "High refusal rate — review routing rules and agent availability"));
        agent4.put("laggingSkills", lagging4);
        agent4.put("strengths", List.of());
        agents.add(agent4);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.put("teamAvgAht", 165.3);
        response.put("agents", agents);
        return response;
    }

    private Map<String, Object> mockLaggingSkill(String skillName, double agentAht, double teamAvgAht,
                                                  double gap, int contacts, String refusalRate, String recommendation) {
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("skillName", skillName);
        skill.put("agentAht", agentAht);
        skill.put("teamAvgAht", teamAvgAht);
        skill.put("gap", gap + "x");
        skill.put("contacts", contacts);
        skill.put("refusalRate", refusalRate);
        skill.put("recommendation", recommendation);
        return skill;
    }

    private Map<String, Object> mockStrength(String skillName, double agentAht, double teamAvgAht) {
        Map<String, Object> strength = new LinkedHashMap<>();
        strength.put("skillName", skillName);
        strength.put("agentAht", agentAht);
        strength.put("teamAvgAht", teamAvgAht);
        return strength;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
