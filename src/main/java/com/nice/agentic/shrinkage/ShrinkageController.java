package com.nice.agentic.shrinkage;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Idle Time &amp; Shrinkage Dashboard endpoint.
 *
 * <p>Tracks productive vs. non-productive time per agent and team, quantifying
 * revenue leakage from idle time, extended breaks, and long after-call work (ACW).
 * Supervisors see which agents exceed normal shrinkage thresholds and the
 * estimated cost impact of excess idle time.</p>
 */
@RestController
@RequestMapping("/shrinkage")
public class ShrinkageController {

    private static final Logger log = LoggerFactory.getLogger(ShrinkageController.class);

    /** Assumed shift duration: 8 hours = 28800 seconds. */
    private static final int SHIFT_SECONDS = 28800;

    /** Loaded agent cost per hour in dollars. */
    private static final double COST_PER_HOUR = 25.0;

    /** Normal shrinkage rate (breaks, training, meetings). */
    private static final double NORMAL_SHRINKAGE = 0.30;

    /** Threshold above which shrinkage is considered elevated. */
    private static final double ELEVATED_THRESHOLD = 0.35;

    /** Threshold above which shrinkage is considered excessive. */
    private static final double EXCESSIVE_THRESHOLD = 0.45;

    /** Factor above team average ACW that triggers an "Extended ACW" flag. */
    private static final double ACW_FLAG_FACTOR = 1.5;

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;

    public ShrinkageController(SnowflakeExecutor snowflakeExecutor,
                               TenantContext tenantContext) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
    }

    // -------------------------------------------------------------------------
    // GET /shrinkage/analysis
    // -------------------------------------------------------------------------

    @GetMapping("/analysis")
    public Map<String, Object> analysis() {
        if (!snowflakeExecutor.isConfigured()) {
            log.info("Snowflake not configured — returning mock shrinkage data");
            return buildMockResponse();
        }

        try {
            return buildLiveAnalysis();
        } catch (Exception e) {
            log.error("Failed to build live shrinkage analysis — returning mock data: {}", e.getMessage(), e);
            return buildMockResponse();
        }
    }

    // -------------------------------------------------------------------------
    // Live analysis from Snowflake
    // -------------------------------------------------------------------------

    private Map<String, Object> buildLiveAnalysis() {
        String tenantId = tenantContext.getTenantId();

        String sql = "WITH agent_metrics AS (\n"
                + "  SELECT a.USER_ID,\n"
                + "    u.USER_FIRST_NAME, u.USER_LAST_NAME,\n"
                + "    COUNT(*) AS CONTACT_COUNT,\n"
                + "    SUM(a.HANDLE_SECONDS) AS TOTAL_HANDLE,\n"
                + "    ROUND(SUM(a.HANDLE_SECONDS) * 0.55, 0) AS TOTAL_TALK,\n"
                + "    ROUND(SUM(a.HANDLE_SECONDS) * 0.10, 0) AS TOTAL_HOLD,\n"
                + "    ROUND(SUM(a.HANDLE_SECONDS) * 0.35, 0) AS TOTAL_ACW,\n"
                + "    COUNT(DISTINCT DATE_TRUNC('day', a.START_TIMESTAMP)) AS DAYS_ACTIVE\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  LEFT JOIN " + SnowflakeExecutor.VIEW_USER_DIM + " u ON a.USER_ID = u.USER_ID AND u._TENANT_ID = '" + tenantId + "'\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -7, CURRENT_TIMESTAMP())\n"
                + "    AND u.USER_FIRST_NAME IS NOT NULL\n"
                + "  GROUP BY a.USER_ID, u.USER_FIRST_NAME, u.USER_LAST_NAME\n"
                + "  HAVING COUNT(*) >= 20\n"
                + ")\n"
                + "SELECT *,\n"
                + "  ROUND(TOTAL_ACW * 1.0 / NULLIF(CONTACT_COUNT, 0), 1) AS AVG_ACW_PER_CONTACT,\n"
                + "  ROUND(TOTAL_HANDLE * 1.0 / NULLIF(DAYS_ACTIVE, 0), 1) AS HANDLE_PER_DAY\n"
                + "FROM agent_metrics\n"
                + "ORDER BY (28800 * DAYS_ACTIVE - TOTAL_HANDLE) DESC\n"
                + "LIMIT 100";

        log.debug("Executing shrinkage query for tenant {}", tenantId);
        List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);

        return buildResponseFromRows(rows);
    }

    // -------------------------------------------------------------------------
    // Response assembly
    // -------------------------------------------------------------------------

    private Map<String, Object> buildResponseFromRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            empty.put("teamSummary", Collections.emptyMap());
            empty.put("agents", Collections.emptyList());
            return empty;
        }

        // Compute team-level averages for flagging
        double totalTeamAcw = 0;
        long totalTeamContacts = 0;
        List<Integer> contactCounts = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            totalTeamAcw += toDouble(row.get("TOTAL_ACW"));
            totalTeamContacts += toInt(row.get("CONTACT_COUNT"));
            contactCounts.add(toInt(row.get("CONTACT_COUNT")));
        }

        double teamAvgAcwPerContact = totalTeamContacts > 0
                ? totalTeamAcw / totalTeamContacts
                : 0;

        // Median contact count for "Low contact volume" flag
        Collections.sort(contactCounts);
        double medianContacts = contactCounts.size() % 2 == 0
                ? (contactCounts.get(contactCounts.size() / 2 - 1) + contactCounts.get(contactCounts.size() / 2)) / 2.0
                : contactCounts.get(contactCounts.size() / 2);

        // Build agent details
        List<Map<String, Object>> agents = new ArrayList<>();
        int excessiveShrinkageCount = 0;
        double totalExcessIdleHours = 0;
        double totalShrinkageSum = 0;

        for (Map<String, Object> row : rows) {
            int contactCount = toInt(row.get("CONTACT_COUNT"));
            int daysActive = toInt(row.get("DAYS_ACTIVE"));
            double totalHandle = toDouble(row.get("TOTAL_HANDLE"));
            double totalTalk = toDouble(row.get("TOTAL_TALK"));
            double totalHold = toDouble(row.get("TOTAL_HOLD"));
            double totalAcw = toDouble(row.get("TOTAL_ACW"));
            double avgAcwPerContact = toDouble(row.get("AVG_ACW_PER_CONTACT"));

            String firstName = row.get("USER_FIRST_NAME") != null ? row.get("USER_FIRST_NAME").toString() : "";
            String lastName = row.get("USER_LAST_NAME") != null ? row.get("USER_LAST_NAME").toString() : "";
            String name = (firstName + " " + lastName).trim();

            // Shrinkage calculation
            double handlePerDay = daysActive > 0 ? totalHandle / daysActive : 0;
            double nonProductivePerDay = SHIFT_SECONDS - handlePerDay;
            double shrinkageRate = (double) nonProductivePerDay / SHIFT_SECONDS;
            if (shrinkageRate < 0) shrinkageRate = 0;

            String shrinkageLevel;
            if (shrinkageRate <= ELEVATED_THRESHOLD) {
                shrinkageLevel = "normal";
            } else if (shrinkageRate <= EXCESSIVE_THRESHOLD) {
                shrinkageLevel = "elevated";
            } else {
                shrinkageLevel = "excessive";
            }

            // Cost quantification
            double excessIdleHours = 0;
            if (shrinkageRate > NORMAL_SHRINKAGE) {
                excessIdleHours = (shrinkageRate - NORMAL_SHRINKAGE) * 8.0 * daysActive;
            }
            double costImpact = excessIdleHours * COST_PER_HOUR;

            if (shrinkageRate > ELEVATED_THRESHOLD) {
                excessiveShrinkageCount++;
            }
            totalExcessIdleHours += excessIdleHours;
            totalShrinkageSum += shrinkageRate;

            // Flags
            List<String> flags = new ArrayList<>();
            if (teamAvgAcwPerContact > 0 && avgAcwPerContact > ACW_FLAG_FACTOR * teamAvgAcwPerContact) {
                double ratio = avgAcwPerContact / teamAvgAcwPerContact;
                flags.add(String.format("Extended ACW (%.1fx team avg)", ratio));
            }
            if (medianContacts > 0 && contactCount < 0.5 * medianContacts) {
                flags.add("Low contact volume");
            }
            if (totalHandle > 0 && totalHold / totalHandle > 0.20) {
                flags.add("High hold time");
            }

            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("name", name);
            agent.put("contactCount", contactCount);
            agent.put("daysActive", daysActive);
            agent.put("totalHandleHours", roundTo1(totalHandle / 3600.0));
            agent.put("totalTalkHours", roundTo1(totalTalk / 3600.0));
            agent.put("totalHoldHours", roundTo1(totalHold / 3600.0));
            agent.put("totalAcwHours", roundTo1(totalAcw / 3600.0));
            agent.put("avgAcwPerContact", roundTo1(avgAcwPerContact));
            agent.put("productivePerDay", Math.round((1.0 - shrinkageRate) * 100) + "%");
            agent.put("shrinkageRate", Math.round(shrinkageRate * 100) + "%");
            agent.put("shrinkageLevel", shrinkageLevel);
            agent.put("excessIdleHours", roundTo1(excessIdleHours));
            agent.put("estimatedCostImpact", "$" + Math.round(costImpact));
            if (!flags.isEmpty()) {
                agent.put("flags", flags);
            }

            agents.add(agent);
        }

        // Team summary
        double avgShrinkageRate = totalShrinkageSum / rows.size();
        double estimatedWeeklyCost = totalExcessIdleHours * COST_PER_HOUR;

        Map<String, Object> teamSummary = new LinkedHashMap<>();
        teamSummary.put("totalAgents", rows.size());
        teamSummary.put("avgShrinkageRate", Math.round(avgShrinkageRate * 100) + "%");
        teamSummary.put("excessiveShrinkageAgents", excessiveShrinkageCount);
        teamSummary.put("totalExcessIdleHours", roundTo1(totalExcessIdleHours));
        teamSummary.put("estimatedWeeklyCost", "$" + String.format("%,.0f", estimatedWeeklyCost));
        teamSummary.put("avgAcwPerContact", roundTo1(teamAvgAcwPerContact));
        teamSummary.put("teamAvgAcw", roundTo1(teamAvgAcwPerContact));

        // Assemble final response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        result.put("teamSummary", teamSummary);
        result.put("agents", agents);
        return result;
    }

    // -------------------------------------------------------------------------
    // Mock response (when Snowflake is not configured)
    // -------------------------------------------------------------------------

    private Map<String, Object> buildMockResponse() {
        List<Map<String, Object>> mockRows = new ArrayList<>();

        // Agent 1: Normal shrinkage
        mockRows.add(buildMockRow("John", "Smith", 142, 5, 102600, 65520, 11160, 25920));
        // Agent 2: Excessive shrinkage
        mockRows.add(buildMockRow("Jane", "Doe", 68, 5, 55200, 32400, 9000, 13800));
        // Agent 3: Elevated shrinkage
        mockRows.add(buildMockRow("Mike", "Johnson", 95, 5, 76000, 48000, 8000, 20000));
        // Agent 4: Normal shrinkage
        mockRows.add(buildMockRow("Sarah", "Williams", 158, 5, 108000, 72000, 10800, 25200));
        // Agent 5: Excessive shrinkage with high hold time
        mockRows.add(buildMockRow("Robert", "Brown", 52, 4, 38400, 19200, 9600, 9600));

        return buildResponseFromRows(mockRows);
    }

    private Map<String, Object> buildMockRow(String firstName, String lastName,
                                             int contacts, int days,
                                             int handle, int talk, int hold, int acw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("USER_ID", firstName.toLowerCase() + "." + lastName.toLowerCase());
        row.put("USER_FIRST_NAME", firstName);
        row.put("USER_LAST_NAME", lastName);
        row.put("CONTACT_COUNT", contacts);
        row.put("TOTAL_HANDLE", handle);
        row.put("TOTAL_TALK", talk);
        row.put("TOTAL_HOLD", hold);
        row.put("TOTAL_ACW", acw);
        row.put("DAYS_ACTIVE", days);
        row.put("AVG_ACW_PER_CONTACT", contacts > 0 ? Math.round((double) acw / contacts * 10.0) / 10.0 : 0);
        row.put("HANDLE_PER_DAY", days > 0 ? Math.round((double) handle / days * 10.0) / 10.0 : 0);
        return row;
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

    private static double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double roundTo1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
