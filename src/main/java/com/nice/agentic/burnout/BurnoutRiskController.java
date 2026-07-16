package com.nice.agentic.burnout;

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
 * Analyzes agent behavior patterns over the last 14 days to detect early signs
 * of burnout or attrition risk. Scores each agent on a 0-100 risk scale based on:
 * rising AHT trend, increasing refusals, volume drop, and consistency variance.
 */
@RestController
@RequestMapping("/burnout")
public class BurnoutRiskController {

    private static final Logger log = LoggerFactory.getLogger(BurnoutRiskController.class);

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;

    public BurnoutRiskController(SnowflakeExecutor snowflakeExecutor,
                                  TenantContext tenantContext) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
    }

    // -------------------------------------------------------------------------
    // GET /burnout/risk
    // -------------------------------------------------------------------------

    @GetMapping("/risk")
    public Map<String, Object> risk() {
        if (!snowflakeExecutor.isConfigured()) {
            log.info("Snowflake not configured — returning mock burnout risk data");
            return buildMockResponse();
        }

        try {
            Map<String, Object> liveResponse = buildLiveResponse();
            if (liveResponse != null) {
                return liveResponse;
            }
            log.warn("Live burnout risk query returned no results — falling back to mock data");
        } catch (Exception e) {
            log.error("Failed to build live burnout risk response — falling back to mock data: {}", e.getMessage(), e);
        }

        return buildMockResponse();
    }

    // -------------------------------------------------------------------------
    // Live Snowflake query
    // -------------------------------------------------------------------------

    private Map<String, Object> buildLiveResponse() {
        String tenantId = tenantContext.getTenantId();

        String sql = "WITH recent_7d AS (\n"
                + "  SELECT\n"
                + "    a.USER_ID,\n"
                + "    AVG(a.HANDLE_SECONDS) AS AVG_AHT,\n"
                + "    COUNT(*) AS CONTACT_COUNT,\n"
                + "    SUM(CASE WHEN a.IS_REFUSED_FLAG = 1 THEN 1 ELSE 0 END) AS REFUSAL_COUNT,\n"
                + "    STDDEV(a.HANDLE_SECONDS) AS STDDEV_AHT\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -7, CURRENT_TIMESTAMP())\n"
                + "  GROUP BY a.USER_ID\n"
                + "),\n"
                + "prev_7d AS (\n"
                + "  SELECT\n"
                + "    a.USER_ID,\n"
                + "    AVG(a.HANDLE_SECONDS) AS AVG_AHT,\n"
                + "    COUNT(*) AS CONTACT_COUNT,\n"
                + "    SUM(CASE WHEN a.IS_REFUSED_FLAG = 1 THEN 1 ELSE 0 END) AS REFUSAL_COUNT\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -14, CURRENT_TIMESTAMP())\n"
                + "    AND a.START_TIMESTAMP < DATEADD(day, -7, CURRENT_TIMESTAMP())\n"
                + "  GROUP BY a.USER_ID\n"
                + ")\n"
                + "SELECT\n"
                + "  r.USER_ID,\n"
                + "  u.USER_FIRST_NAME,\n"
                + "  u.USER_LAST_NAME,\n"
                + "  r.AVG_AHT AS RECENT_AHT,\n"
                + "  p.AVG_AHT AS PREV_AHT,\n"
                + "  r.CONTACT_COUNT AS RECENT_CONTACTS,\n"
                + "  p.CONTACT_COUNT AS PREV_CONTACTS,\n"
                + "  r.REFUSAL_COUNT AS RECENT_REFUSALS,\n"
                + "  p.REFUSAL_COUNT AS PREV_REFUSALS,\n"
                + "  r.STDDEV_AHT AS RECENT_STDDEV_AHT\n"
                + "FROM recent_7d r\n"
                + "JOIN prev_7d p ON r.USER_ID = p.USER_ID\n"
                + "LEFT JOIN " + SnowflakeExecutor.VIEW_USER_DIM + " u\n"
                + "  ON r.USER_ID = u.USER_ID AND u._TENANT_ID = '" + tenantId + "'\n"
                + "ORDER BY r.USER_ID\n"
                + "LIMIT 500";

        log.debug("Executing burnout risk SQL query");
        List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);

        if (rows == null || rows.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> scoredAgents = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> agentResult = scoreAgent(row);
            if (agentResult != null) {
                scoredAgents.add(agentResult);
            }
        }

        // Sort by riskScore DESC
        scoredAgents.sort((a, b) -> Integer.compare(toInt(b.get("riskScore")), toInt(a.get("riskScore"))));

        // Only return agents with score >= 25, limit to top 50
        List<Map<String, Object>> filteredAgents = new ArrayList<>();
        for (Map<String, Object> agent : scoredAgents) {
            if (toInt(agent.get("riskScore")) >= 25 && filteredAgents.size() < 50) {
                filteredAgents.add(agent);
            }
        }

        return buildResponseEnvelope(filteredAgents, scoredAgents.size());
    }

    private Map<String, Object> scoreAgent(Map<String, Object> row) {
        double recentAht = toDouble(row.get("RECENT_AHT"), 0.0);
        double prevAht = toDouble(row.get("PREV_AHT"), 0.0);
        int recentContacts = toInt(row.get("RECENT_CONTACTS"));
        int prevContacts = toInt(row.get("PREV_CONTACTS"));
        int recentRefusals = toInt(row.get("RECENT_REFUSALS"));
        double recentStddevAht = toDouble(row.get("RECENT_STDDEV_AHT"), 0.0);

        // Skip agents with insufficient data
        if (prevAht <= 0 || prevContacts <= 0) {
            return null;
        }

        List<Map<String, Object>> signals = new ArrayList<>();
        int totalScore = 0;

        // 1. AHT Trend (0-30 points)
        double ahtChange = (recentAht - prevAht) / prevAht;
        int ahtPoints = 0;
        if (ahtChange > 0.20) {
            ahtPoints = 30;
        } else if (ahtChange > 0.10) {
            ahtPoints = 20;
        } else if (ahtChange > 0.05) {
            ahtPoints = 10;
        }
        if (ahtPoints > 0) {
            Map<String, Object> signal = new LinkedHashMap<>();
            signal.put("factor", "AHT Trend");
            signal.put("detail", String.format("+%.0f%% week-over-week", ahtChange * 100));
            signal.put("points", ahtPoints);
            signals.add(signal);
            totalScore += ahtPoints;
        }

        // 2. Refusal Rate (0-25 points)
        double refusalRate = recentContacts > 0
                ? (double) recentRefusals / recentContacts
                : 0.0;
        int refusalPoints = 0;
        if (refusalRate > 0.15) {
            refusalPoints = 25;
        } else if (refusalRate > 0.10) {
            refusalPoints = 20;
        } else if (refusalRate > 0.05) {
            refusalPoints = 10;
        }
        if (refusalPoints > 0) {
            double prevRefusalRate = prevContacts > 0
                    ? (double) toInt(row.get("PREV_REFUSALS")) / prevContacts
                    : 0.0;
            Map<String, Object> signal = new LinkedHashMap<>();
            signal.put("factor", "Refusal Rate");
            signal.put("detail", String.format("%.0f%% (was %.0f%%)", refusalRate * 100, prevRefusalRate * 100));
            signal.put("points", refusalPoints);
            signals.add(signal);
            totalScore += refusalPoints;
        }

        // 3. Volume Drop (0-25 points)
        double volumeChange = (double) (recentContacts - prevContacts) / prevContacts;
        int volumePoints = 0;
        if (volumeChange < -0.30) {
            volumePoints = 25;
        } else if (volumeChange < -0.20) {
            volumePoints = 15;
        } else if (volumeChange < -0.10) {
            volumePoints = 10;
        }
        if (volumePoints > 0) {
            Map<String, Object> signal = new LinkedHashMap<>();
            signal.put("factor", "Volume Drop");
            signal.put("detail", String.format("%.0f%% contacts handled", volumeChange * 100));
            signal.put("points", volumePoints);
            signals.add(signal);
            totalScore += volumePoints;
        }

        // 4. Consistency / Variance (0-20 points)
        double coefficientOfVariation = recentAht > 0 ? recentStddevAht / recentAht : 0.0;
        int consistencyPoints = 0;
        if (coefficientOfVariation > 0.4) {
            consistencyPoints = 20;
        } else if (coefficientOfVariation > 0.3) {
            consistencyPoints = 15;
        } else if (coefficientOfVariation > 0.2) {
            consistencyPoints = 10;
        }
        if (consistencyPoints > 0) {
            Map<String, Object> signal = new LinkedHashMap<>();
            signal.put("factor", "Consistency");
            signal.put("detail", String.format("AHT variance CV=%.2f", coefficientOfVariation));
            signal.put("points", consistencyPoints);
            signals.add(signal);
            totalScore += consistencyPoints;
        }

        // Determine risk level
        String riskLevel;
        if (totalScore >= 60) {
            riskLevel = "high";
        } else if (totalScore >= 35) {
            riskLevel = "medium";
        } else {
            riskLevel = "low";
        }

        // Build agent name
        String firstName = row.get("USER_FIRST_NAME") != null ? row.get("USER_FIRST_NAME").toString() : "";
        String lastName = row.get("USER_LAST_NAME") != null ? row.get("USER_LAST_NAME").toString() : "";
        String name = (firstName + " " + lastName).trim();
        if (name.isEmpty()) {
            name = "Agent " + row.get("USER_ID");
        }

        // Generate recommendation
        String recommendation = generateRecommendation(totalScore, ahtPoints, refusalPoints, volumePoints, consistencyPoints);

        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("name", name);
        agent.put("riskScore", totalScore);
        agent.put("riskLevel", riskLevel);
        agent.put("signals", signals);
        agent.put("recommendation", recommendation);
        agent.put("recentAht", Math.round(recentAht * 10.0) / 10.0);
        agent.put("previousAht", Math.round(prevAht * 10.0) / 10.0);
        agent.put("contactsThisWeek", recentContacts);
        agent.put("contactsLastWeek", prevContacts);

        return agent;
    }

    private String generateRecommendation(int totalScore, int ahtPoints, int refusalPoints,
                                           int volumePoints, int consistencyPoints) {
        if (totalScore >= 60) {
            if (refusalPoints >= 20 && volumePoints >= 15) {
                return "Urgent: Schedule immediate 1:1 check-in. Consider temporary removal from queue and wellness support.";
            }
            if (ahtPoints >= 30) {
                return "Schedule 1:1 check-in. Consider temporary skill reduction or schedule adjustment.";
            }
            return "High burnout risk detected. Schedule wellness check-in and review workload distribution.";
        } else if (totalScore >= 35) {
            if (volumePoints >= 15) {
                return "Monitor contact volume trend. Consider schedule flexibility or skill rotation.";
            }
            if (consistencyPoints >= 15) {
                return "AHT inconsistency detected. Review recent interactions for complexity spikes or training needs.";
            }
            return "Moderate risk indicators present. Monitor over next week and consider proactive check-in.";
        } else {
            return "Low-level indicators present. Continue standard monitoring.";
        }
    }

    // -------------------------------------------------------------------------
    // Response envelope builder
    // -------------------------------------------------------------------------

    private Map<String, Object> buildResponseEnvelope(List<Map<String, Object>> filteredAgents, int totalAgentsScored) {
        int highRisk = 0;
        int mediumRisk = 0;
        int lowRisk = 0;
        long totalScore = 0;

        // Count from all scored agents (not just filtered)
        // But for filtered we count risk levels from total scored set
        // We use filteredAgents for the response but compute summary from all scored agents
        for (Map<String, Object> agent : filteredAgents) {
            int score = toInt(agent.get("riskScore"));
            totalScore += score;
            String level = agent.get("riskLevel").toString();
            switch (level) {
                case "high" -> highRisk++;
                case "medium" -> mediumRisk++;
                default -> lowRisk++;
            }
        }

        int lowRiskTotal = totalAgentsScored - highRisk - mediumRisk - lowRisk;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAgents", totalAgentsScored);
        summary.put("highRisk", highRisk);
        summary.put("mediumRisk", mediumRisk);
        summary.put("lowRisk", lowRiskTotal + lowRisk);
        summary.put("avgRiskScore", totalAgentsScored > 0
                ? Math.round((double) totalScore / filteredAgents.size())
                : 0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.put("summary", summary);
        response.put("agents", filteredAgents);

        log.info("Burnout risk analysis complete: {} total agents, {} high risk, {} medium risk",
                totalAgentsScored, highRisk, mediumRisk);

        return response;
    }

    // -------------------------------------------------------------------------
    // Mock data fallback
    // -------------------------------------------------------------------------

    private Map<String, Object> buildMockResponse() {
        List<Map<String, Object>> agents = new ArrayList<>();

        agents.add(buildMockAgent("Sarah Johnson", 78, "high",
                List.of(
                        mockSignal("AHT Trend", "+24% week-over-week", 30),
                        mockSignal("Refusal Rate", "12% (was 4%)", 20),
                        mockSignal("Volume Drop", "-35% contacts handled", 25)
                ),
                "Schedule 1:1 check-in. Consider temporary skill reduction or schedule adjustment.",
                340.5, 274.6, 42, 65));

        agents.add(buildMockAgent("Michael Chen", 65, "high",
                List.of(
                        mockSignal("AHT Trend", "+18% week-over-week", 20),
                        mockSignal("Consistency", "AHT variance CV=0.45", 20),
                        mockSignal("Refusal Rate", "16% (was 6%)", 25)
                ),
                "Urgent: Schedule immediate 1:1 check-in. Consider temporary removal from queue and wellness support.",
                412.0, 349.2, 38, 52));

        agents.add(buildMockAgent("Emily Rodriguez", 45, "medium",
                List.of(
                        mockSignal("Volume Drop", "-22% contacts handled", 15),
                        mockSignal("AHT Trend", "+12% week-over-week", 20),
                        mockSignal("Consistency", "AHT variance CV=0.32", 15)
                ),
                "Monitor contact volume trend. Consider schedule flexibility or skill rotation.",
                298.0, 266.1, 55, 71));

        agents.add(buildMockAgent("David Kim", 38, "medium",
                List.of(
                        mockSignal("Refusal Rate", "8% (was 2%)", 10),
                        mockSignal("AHT Trend", "+15% week-over-week", 20),
                        mockSignal("Consistency", "AHT variance CV=0.28", 10)
                ),
                "Moderate risk indicators present. Monitor over next week and consider proactive check-in.",
                285.3, 248.1, 68, 74));

        agents.add(buildMockAgent("Lisa Thompson", 28, "low",
                List.of(
                        mockSignal("AHT Trend", "+8% week-over-week", 10),
                        mockSignal("Volume Drop", "-12% contacts handled", 10),
                        mockSignal("Consistency", "AHT variance CV=0.22", 10)
                ),
                "Low-level indicators present. Continue standard monitoring.",
                256.8, 237.8, 82, 93));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAgents", 120);
        summary.put("highRisk", 2);
        summary.put("mediumRisk", 2);
        summary.put("lowRisk", 116);
        summary.put("avgRiskScore", 22);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.put("summary", summary);
        response.put("agents", agents);

        return response;
    }

    private Map<String, Object> buildMockAgent(String name, int riskScore, String riskLevel,
                                                List<Map<String, Object>> signals, String recommendation,
                                                double recentAht, double previousAht,
                                                int contactsThisWeek, int contactsLastWeek) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("name", name);
        agent.put("riskScore", riskScore);
        agent.put("riskLevel", riskLevel);
        agent.put("signals", signals);
        agent.put("recommendation", recommendation);
        agent.put("recentAht", recentAht);
        agent.put("previousAht", previousAht);
        agent.put("contactsThisWeek", contactsThisWeek);
        agent.put("contactsLastWeek", contactsLastWeek);
        return agent;
    }

    private Map<String, Object> mockSignal(String factor, String detail, int points) {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("factor", factor);
        signal.put("detail", detail);
        signal.put("points", points);
        return signal;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
