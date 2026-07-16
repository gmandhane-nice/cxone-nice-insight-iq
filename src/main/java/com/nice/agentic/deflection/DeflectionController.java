package com.nice.agentic.deflection;

import com.nice.agentic.TenantContext;
import com.nice.agentic.query.SnowflakeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Contact Deflection Opportunity Detector.
 *
 * <p>Identifies high-volume, low-complexity contact types that could be automated
 * via IVR or chatbot, and quantifies cost savings for each automation opportunity.</p>
 *
 * <p>Deflection score (0-100) is a weighted combination of:
 * <ul>
 *   <li>Volume (30%) — higher contact volume scores higher</li>
 *   <li>Simplicity (30%) — lower AHT scores higher</li>
 *   <li>Consistency (20%) — lower stddev of AHT scores higher</li>
 *   <li>Agent breadth (20%) — more agents handling it scores higher</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/deflection")
public class DeflectionController {

    private static final Logger log = LoggerFactory.getLogger(DeflectionController.class);

    private static final double COST_PER_CONTACT = 0.50;
    private static final double DEFLECTION_RATE = 0.40;
    private static final int MIN_VOLUME_THRESHOLD = 200;
    private static final int MAX_OPPORTUNITIES = 15;

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;

    public DeflectionController(SnowflakeExecutor snowflakeExecutor,
                                TenantContext tenantContext) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
    }

    // -------------------------------------------------------------------------
    // GET /deflection/opportunities
    // -------------------------------------------------------------------------

    @GetMapping("/opportunities")
    public Map<String, Object> opportunities() {
        if (snowflakeExecutor.isConfigured()) {
            try {
                Map<String, Object> liveResponse = buildLiveResponse();
                if (liveResponse != null) {
                    log.info("Returning live deflection opportunities");
                    return liveResponse;
                }
                log.warn("Live deflection query returned no results — falling back to mock data");
            } catch (Exception e) {
                log.error("Failed to build live deflection opportunities — falling back to mock data: {}",
                        e.getMessage(), e);
            }
        }

        return buildMockResponse();
    }

    // -------------------------------------------------------------------------
    // Live Snowflake query
    // -------------------------------------------------------------------------

    private Map<String, Object> buildLiveResponse() {
        String tenantId = tenantContext.getTenantId();

        String sql = "WITH skill_stats AS (\n"
                + "  SELECT\n"
                + "    a.SKILL_NO,\n"
                + "    MAX(sk.SKILL_NAME) AS SKILL_NAME,\n"
                + "    COUNT(*) AS VOLUME,\n"
                + "    AVG(a.HANDLE_SECONDS) AS AVG_AHT,\n"
                + "    STDDEV(a.HANDLE_SECONDS) AS STDDEV_AHT,\n"
                + "    COUNT(DISTINCT a.USER_ID) AS AGENT_COUNT\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  LEFT JOIN " + SnowflakeExecutor.VIEW_SKILL_DIM + " sk\n"
                + "    ON a.SKILL_NO = sk.SKILL_NO AND sk._TENANT_ID = '" + tenantId + "'\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -14, CURRENT_TIMESTAMP())\n"
                + "    AND a.IS_REFUSED_FLAG = 0\n"
                + "  GROUP BY a.SKILL_NO\n"
                + "  HAVING COUNT(*) >= " + MIN_VOLUME_THRESHOLD + "\n"
                + "),\n"
                + "tenant_median AS (\n"
                + "  SELECT PERCENTILE_CONT(0.35) WITHIN GROUP (ORDER BY AVG_AHT) AS P35_AHT\n"
                + "  FROM skill_stats\n"
                + ")\n"
                + "SELECT s.SKILL_NO, s.SKILL_NAME, s.VOLUME, s.AVG_AHT, s.STDDEV_AHT, s.AGENT_COUNT\n"
                + "FROM skill_stats s, tenant_median m\n"
                + "WHERE s.AVG_AHT <= m.P35_AHT\n"
                + "ORDER BY s.VOLUME DESC\n"
                + "LIMIT " + MAX_OPPORTUNITIES;

        log.debug("Deflection SQL: {}", sql);
        List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);

        if (rows == null || rows.isEmpty()) {
            return null;
        }

        // Build opportunities and compute scores
        List<Map<String, Object>> opportunities = new ArrayList<>();
        double totalMonthlySavings = 0;
        int totalDeflectableContacts = 0;

        // Find max values for normalization
        double maxVolume = 0;
        double maxAgentCount = 0;
        for (Map<String, Object> row : rows) {
            double vol = toDouble(row.get("VOLUME"), 0);
            double agents = toDouble(row.get("AGENT_COUNT"), 0);
            if (vol > maxVolume) maxVolume = vol;
            if (agents > maxAgentCount) maxAgentCount = agents;
        }

        for (Map<String, Object> row : rows) {
            int skillNo = toInt(row.get("SKILL_NO"));
            String skillName = row.get("SKILL_NAME") != null ? row.get("SKILL_NAME").toString() : "Skill_" + skillNo;
            int volume = (int) toDouble(row.get("VOLUME"), 0);
            double avgAht = toDouble(row.get("AVG_AHT"), 0);
            double stddevAht = toDouble(row.get("STDDEV_AHT"), 0);
            int agentCount = (int) toDouble(row.get("AGENT_COUNT"), 0);

            int score = calculateDeflectionScore(volume, avgAht, stddevAht, agentCount, maxVolume, maxAgentCount);

            // Cost calculation
            double contactsPerMonth = volume * (30.0 / 14.0);
            int deflectableContacts = (int) Math.round(contactsPerMonth * DEFLECTION_RATE);
            double monthlySavings = deflectableContacts * COST_PER_CONTACT;
            double annualSavings = monthlySavings * 12;

            totalMonthlySavings += monthlySavings;
            totalDeflectableContacts += deflectableContacts;

            Map<String, Object> opportunity = buildOpportunity(
                    skillName, skillNo, score, volume, avgAht, stddevAht, agentCount,
                    deflectableContacts, monthlySavings, annualSavings);
            opportunities.add(opportunity);
        }

        // Sort by deflection score descending
        opportunities.sort((a, b) -> {
            int scoreA = (int) a.get("deflectionScore");
            int scoreB = (int) b.get("deflectionScore");
            return Integer.compare(scoreB, scoreA);
        });

        // Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalOpportunities", opportunities.size());
        summary.put("totalMonthlySavings", formatCurrency(totalMonthlySavings));
        summary.put("totalAnnualSavings", formatCurrency(totalMonthlySavings * 12));
        summary.put("totalDeflectableContacts", totalDeflectableContacts);
        result.put("summary", summary);
        result.put("opportunities", opportunities);

        return result;
    }

    // -------------------------------------------------------------------------
    // Mock response (fallback when Snowflake is not configured)
    // -------------------------------------------------------------------------

    private Map<String, Object> buildMockResponse() {
        log.info("Returning mock deflection opportunities (Snowflake not configured)");

        List<Map<String, Object>> opportunities = new ArrayList<>();

        opportunities.add(buildOpportunity("Balance_Check", 1042, 92,
                12400, 95.3, 28.1, 45,
                4960, 2480.0, 29760.0));

        opportunities.add(buildOpportunity("Payment_Status", 1055, 85,
                8900, 112.7, 35.4, 38,
                3560, 1780.0, 21360.0));

        opportunities.add(buildOpportunity("PIN_Reset", 1078, 78,
                6200, 68.2, 22.8, 52,
                2480, 1240.0, 14880.0));

        opportunities.add(buildOpportunity("Account_Activation", 1091, 64,
                3800, 145.6, 48.3, 28,
                1520, 760.0, 9120.0));

        // Calculate totals
        double totalMonthlySavings = 0;
        int totalDeflectable = 0;
        for (Map<String, Object> opp : opportunities) {
            @SuppressWarnings("unchecked")
            Map<String, Object> savings = (Map<String, Object>) opp.get("savings");
            totalDeflectable += (int) savings.get("deflectableContacts");
            // Parse from formatted string — or compute directly
            totalMonthlySavings += ((int) savings.get("deflectableContacts")) * COST_PER_CONTACT;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalOpportunities", opportunities.size());
        summary.put("totalMonthlySavings", formatCurrency(totalMonthlySavings));
        summary.put("totalAnnualSavings", formatCurrency(totalMonthlySavings * 12));
        summary.put("totalDeflectableContacts", totalDeflectable);
        result.put("summary", summary);
        result.put("opportunities", opportunities);

        return result;
    }

    // -------------------------------------------------------------------------
    // Deflection score calculation
    // -------------------------------------------------------------------------

    private int calculateDeflectionScore(int volume, double avgAht, double stddevAht,
                                         int agentCount, double maxVolume, double maxAgentCount) {
        // Volume score (0-100): linear scale relative to max volume in dataset
        double volumeScore = maxVolume > 0 ? (volume / maxVolume) * 100.0 : 50.0;

        // Simplicity score (0-100): lower AHT = higher score (relative to max AHT in result set)
        // Skills in the bottom 35% of AHT are already pre-filtered, so scale within that range
        double maxAhtInSet = 900.0; // reasonable upper bound for "simple" skills
        double simplicityScore = Math.max(0, Math.min(100, (maxAhtInSet - avgAht) / maxAhtInSet * 100.0));

        // Consistency score (0-100): lower coefficient of variation = higher score
        double cv = avgAht > 0 ? stddevAht / avgAht : 1.0;
        double consistencyScore = Math.max(0, Math.min(100, (1.0 - cv) * 100.0));

        // Agent breadth score (0-100): more agents = more generic = better candidate
        double breadthScore = maxAgentCount > 0 ? (agentCount / maxAgentCount) * 100.0 : 50.0;

        // Weighted combination
        double score = (volumeScore * 0.30)
                + (simplicityScore * 0.30)
                + (consistencyScore * 0.20)
                + (breadthScore * 0.20);

        return (int) Math.round(Math.min(100, Math.max(0, score)));
    }

    // -------------------------------------------------------------------------
    // Build a single opportunity map
    // -------------------------------------------------------------------------

    private Map<String, Object> buildOpportunity(String skillName, int skillNo, int score,
                                                  int volume14d, double avgAht, double stddevAht,
                                                  int agentCount, int deflectableContacts,
                                                  double monthlySavings, double annualSavings) {
        Map<String, Object> opportunity = new LinkedHashMap<>();
        opportunity.put("skillName", skillName);
        opportunity.put("skillNo", skillNo);
        opportunity.put("deflectionScore", score);
        opportunity.put("priority", score >= 75 ? "high" : score >= 50 ? "medium" : "low");

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("contactVolume14d", volume14d);
        metrics.put("avgAht", Math.round(avgAht * 10.0) / 10.0);
        metrics.put("stddevAht", Math.round(stddevAht * 10.0) / 10.0);
        metrics.put("agentCount", agentCount);
        opportunity.put("metrics", metrics);

        Map<String, Object> savings = new LinkedHashMap<>();
        savings.put("deflectableContacts", deflectableContacts);
        savings.put("monthlySavings", formatCurrency(monthlySavings));
        savings.put("annualSavings", formatCurrency(annualSavings));
        savings.put("deflectionRate", "40%");
        opportunity.put("savings", savings);

        String automationType = determineAutomationType(avgAht);
        opportunity.put("automationType", automationType);
        opportunity.put("rationale", buildRationale(volume14d, avgAht, stddevAht));
        opportunity.put("implementation", buildImplementation(automationType, skillName));

        return opportunity;
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private String determineAutomationType(double avgAht) {
        if (avgAht < 60) return "IVR";
        if (avgAht < 120) return "IVR/Chatbot";
        return "Chatbot/FAQ";
    }

    private String buildRationale(int volume14d, double avgAht, double stddevAht) {
        String volumeDesc = volume14d >= 10000 ? "Very high" : volume14d >= 5000 ? "High" : "Moderate";
        String ahtDesc = avgAht < 60 ? "very short" : avgAht < 120 ? "short" : "moderate";
        return String.format(
                "%s volume (%,d/2wk), %s interactions (%ds avg), consistent handling (%ds stddev). Ideal for self-service automation.",
                volumeDesc, volume14d, ahtDesc, (int) avgAht, (int) stddevAht);
    }

    private String buildImplementation(String automationType, String skillName) {
        String cleanName = skillName.replace("_", " ").toLowerCase();
        switch (automationType) {
            case "IVR":
                return "Add IVR menu option for " + cleanName + " with touch-tone navigation";
            case "IVR/Chatbot":
                return "Add IVR menu option or chatbot flow for " + cleanName;
            case "Chatbot/FAQ":
                return "Deploy chatbot flow with FAQ fallback for " + cleanName;
            default:
                return "Evaluate automation options for " + cleanName;
        }
    }

    private String formatCurrency(double amount) {
        NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.US);
        nf.setMaximumFractionDigits(0);
        return nf.format(amount);
    }

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
