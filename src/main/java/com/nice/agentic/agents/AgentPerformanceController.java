package com.nice.agentic.agents;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller providing agent performance metrics for the Agent View dashboard.
 * Queries Snowflake for agent-level contact metrics (AHT, talk time, hold, ACW, SLA).
 */
@RestController
@RequestMapping("/agents")
public class AgentPerformanceController {

    private static final Logger log = LoggerFactory.getLogger(AgentPerformanceController.class);

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;

    public AgentPerformanceController(SnowflakeExecutor snowflakeExecutor, TenantContext tenantContext) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/performance")
    public Map<String, Object> getPerformance() {
        if (!snowflakeExecutor.isConfigured()) {
            log.info("Snowflake not configured — returning mock agent performance data");
            return buildMockResponse();
        }

        try {
            // Try last 7 days first
            List<Map<String, Object>> rows = queryAgentMetrics(7);
            if (rows.isEmpty()) {
                log.info("No agent data in last 7 days — widening to 720 days for demo");
                rows = queryAgentMetrics(720);
            }
            if (rows.isEmpty()) {
                log.warn("No agent performance data found — returning mock data");
                return buildMockResponse();
            }
            return buildResponse(rows);
        } catch (Exception e) {
            log.error("Failed to query agent performance — returning mock data: {}", e.getMessage(), e);
            return buildMockResponse();
        }
    }

    private List<Map<String, Object>> queryAgentMetrics(int daysBack) {
        String tenantId = tenantContext.getTenantId();
        String sql = "SELECT \n"
                + "  u.USER_FIRST_NAME,\n"
                + "  u.USER_LAST_NAME,\n"
                + "  COUNT(*) AS CONTACT_COUNT,\n"
                + "  ROUND(AVG(a.AGENT_CONTACT_DURATION_SECONDS), 1) AS AVG_HANDLE_TIME,\n"
                + "  ROUND(AVG(a.TALK_TIME_SECONDS), 1) AS AVG_TALK_TIME,\n"
                + "  ROUND(AVG(a.HOLD_SECONDS), 1) AS AVG_HOLD_TIME,\n"
                + "  ROUND(AVG(a.ACW_SECONDS), 1) AS AVG_ACW,\n"
                + "  ROUND(SUM(CASE WHEN a.AGENT_CONTACT_DURATION_SECONDS < 300 THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(*), 0), 1) AS SLA_PCT\n"
                + "FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "LEFT JOIN " + SnowflakeExecutor.VIEW_USER_DIM + " u\n"
                + "  ON a.USER_ID = u.USER_ID AND u._TENANT_ID = '" + tenantId + "'\n"
                + "WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "  AND a.START_TIMESTAMP >= DATEADD(day, -" + daysBack + ", CURRENT_TIMESTAMP())\n"
                + "GROUP BY u.USER_FIRST_NAME, u.USER_LAST_NAME\n"
                + "ORDER BY AVG_HANDLE_TIME DESC\n"
                + "LIMIT 50";

        return snowflakeExecutor.execute(sql);
    }

    private Map<String, Object> buildResponse(List<Map<String, Object>> rows) {
        // Calculate team averages for performance level determination
        double totalAht = 0;
        double totalSla = 0;
        int count = 0;
        for (Map<String, Object> row : rows) {
            double aht = toDouble(row.get("AVG_HANDLE_TIME"), 0);
            double sla = toDouble(row.get("SLA_PCT"), 0);
            totalAht += aht;
            totalSla += sla;
            count++;
        }
        double teamAvgAht = count > 0 ? totalAht / count : 0;
        double teamSla = count > 0 ? totalSla / count : 0;

        // Build agent list
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("firstName", row.get("USER_FIRST_NAME") != null ? row.get("USER_FIRST_NAME").toString() : "Unknown");
            agent.put("lastName", row.get("USER_LAST_NAME") != null ? row.get("USER_LAST_NAME").toString() : "");
            agent.put("contactCount", toInt(row.get("CONTACT_COUNT")));
            agent.put("avgHandleTime", toDouble(row.get("AVG_HANDLE_TIME"), 0));
            agent.put("avgTalkTime", toDouble(row.get("AVG_TALK_TIME"), 0));
            agent.put("avgHoldTime", toDouble(row.get("AVG_HOLD_TIME"), 0));
            agent.put("avgAcw", toDouble(row.get("AVG_ACW"), 0));
            agent.put("slaPct", toDouble(row.get("SLA_PCT"), 0));

            // Performance level based on comparison to team average
            double agentAht = toDouble(row.get("AVG_HANDLE_TIME"), 0);
            String level;
            if (teamAvgAht > 0 && agentAht > 2 * teamAvgAht) {
                level = "critical";
            } else if (teamAvgAht > 0 && agentAht > 1.5 * teamAvgAht) {
                level = "attention";
            } else {
                level = "good";
            }
            agent.put("performanceLevel", level);
            agents.add(agent);
        }

        // Build team metrics summary
        Map<String, Object> teamMetrics = new LinkedHashMap<>();
        teamMetrics.put("totalAgents", count);
        teamMetrics.put("avgHandleTime", Math.round(teamAvgAht * 10.0) / 10.0);
        teamMetrics.put("teamSla", Math.round(teamSla * 10.0) / 10.0);

        // Final response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.put("teamMetrics", teamMetrics);
        response.put("agents", agents);
        return response;
    }

    private Map<String, Object> buildMockResponse() {
        List<Map<String, Object>> agents = new ArrayList<>();

        agents.add(mockAgent("Sarah", "Johnson", 142, 245.3, 180.2, 15.1, 50.0, 94.5, "good"));
        agents.add(mockAgent("Mike", "Chen", 128, 312.7, 220.5, 32.2, 60.0, 88.2, "attention"));
        agents.add(mockAgent("Lisa", "Williams", 156, 198.4, 150.3, 8.1, 40.0, 96.8, "good"));
        agents.add(mockAgent("James", "Brown", 95, 520.6, 310.4, 85.2, 125.0, 72.1, "critical"));
        agents.add(mockAgent("Emily", "Davis", 134, 267.9, 195.6, 22.3, 50.0, 91.3, "good"));

        double totalAht = 245.3 + 312.7 + 198.4 + 520.6 + 267.9;
        double teamAvg = totalAht / 5;
        double teamSla = (94.5 + 88.2 + 96.8 + 72.1 + 91.3) / 5;

        Map<String, Object> teamMetrics = new LinkedHashMap<>();
        teamMetrics.put("totalAgents", 5);
        teamMetrics.put("avgHandleTime", Math.round(teamAvg * 10.0) / 10.0);
        teamMetrics.put("teamSla", Math.round(teamSla * 10.0) / 10.0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.put("teamMetrics", teamMetrics);
        response.put("agents", agents);
        return response;
    }

    private Map<String, Object> mockAgent(String first, String last, int contacts,
                                           double aht, double talk, double hold,
                                           double acw, double sla, String level) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("firstName", first);
        agent.put("lastName", last);
        agent.put("contactCount", contacts);
        agent.put("avgHandleTime", aht);
        agent.put("avgTalkTime", talk);
        agent.put("avgHoldTime", hold);
        agent.put("avgAcw", acw);
        agent.put("slaPct", sla);
        agent.put("performanceLevel", level);
        return agent;
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
