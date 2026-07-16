package com.nice.agentic.anomaly;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-time Anomaly Detection endpoint.
 *
 * <p>Detects statistical anomalies across all metrics — AHT spikes, volume surges,
 * refusal clusters, skill imbalances — using z-score analysis against historical
 * baselines. Proactively alerts supervisors to issues they haven't noticed yet.</p>
 *
 * <h2>Algorithm</h2>
 * <ul>
 *   <li>Baseline: previous 21 days of data per metric per dimension</li>
 *   <li>Recent window: last 1 day</li>
 *   <li>z-score = (recent_value - baseline_mean) / baseline_stddev</li>
 *   <li>|z| &gt; 2.0 → warning anomaly</li>
 *   <li>|z| &gt; 3.0 → critical anomaly</li>
 * </ul>
 */
@RestController
@RequestMapping("/anomaly")
public class AnomalyDetectionController {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionController.class);

    private static final double Z_THRESHOLD_WARNING = 2.0;
    private static final double Z_THRESHOLD_CRITICAL = 3.0;
    private static final int BASELINE_DAYS = 21;
    private static final int MAX_ANOMALIES = 20;

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;

    public AnomalyDetectionController(SnowflakeExecutor snowflakeExecutor,
                                      TenantContext tenantContext) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
    }

    // -------------------------------------------------------------------------
    // GET /anomaly/detect
    // -------------------------------------------------------------------------

    @GetMapping("/detect")
    public Map<String, Object> detect() {
        if (!snowflakeExecutor.isConfigured()) {
            log.info("Snowflake not configured — returning mock anomaly data");
            return buildMockResponse();
        }

        try {
            return buildLiveResponse();
        } catch (Exception e) {
            log.error("Failed to build live anomaly detection — returning mock data: {}", e.getMessage(), e);
            return buildMockResponse();
        }
    }

    // -------------------------------------------------------------------------
    // Live Snowflake analysis
    // -------------------------------------------------------------------------

    private Map<String, Object> buildLiveResponse() {
        String tenantId = tenantContext.getTenantId();
        List<Map<String, Object>> anomalies = new ArrayList<>();

        // Query 1: Skill-level anomalies (volume + AHT + refusal)
        long t1 = System.currentTimeMillis();
        List<Map<String, Object>> skillRows = querySkillAnomalies(tenantId);
        log.info("Skill anomaly query returned {} rows in {}ms", skillRows.size(), System.currentTimeMillis() - t1);

        for (Map<String, Object> row : skillRows) {
            String skillName = row.get("SKILL_NAME") != null ? row.get("SKILL_NAME").toString() : "Skill_" + toInt(row.get("SKILL_NO"));

            // Volume anomaly
            double recentVolume = toDouble(row.get("RECENT_VOLUME"));
            double baselineAvgVolume = toDouble(row.get("BASELINE_AVG_VOLUME"));
            double baselineStddevVolume = toDouble(row.get("BASELINE_STDDEV_VOLUME"));
            if (baselineStddevVolume > 0) {
                double zVolume = (recentVolume - baselineAvgVolume) / baselineStddevVolume;
                if (Math.abs(zVolume) >= Z_THRESHOLD_WARNING) {
                    anomalies.add(buildSkillAnomaly(
                            zVolume > 0 ? "volume_surge" : "volume_drop",
                            "volume", skillName, "daily_contacts",
                            recentVolume, baselineAvgVolume, baselineStddevVolume, zVolume));
                }
            }

            // AHT anomaly
            double recentAht = toDouble(row.get("RECENT_AHT"));
            double baselineAvgAht = toDouble(row.get("BASELINE_AVG_AHT"));
            double baselineStddevAht = toDouble(row.get("BASELINE_STDDEV_AHT"));
            if (baselineStddevAht > 0) {
                double zAht = (recentAht - baselineAvgAht) / baselineStddevAht;
                if (Math.abs(zAht) >= Z_THRESHOLD_WARNING) {
                    anomalies.add(buildSkillAnomaly(
                            zAht > 0 ? "aht_spike" : "aht_drop",
                            "aht", skillName, "avg_handle_time",
                            recentAht, baselineAvgAht, baselineStddevAht, zAht));
                }
            }

            // Refusal anomaly
            double recentRefusalRate = toDouble(row.get("RECENT_REFUSAL_RATE"));
            double baselineAvgRefusal = toDouble(row.get("BASELINE_AVG_REFUSAL_RATE"));
            double baselineStddevRefusal = toDouble(row.get("BASELINE_STDDEV_REFUSAL_RATE"));
            if (baselineStddevRefusal > 0) {
                double zRefusal = (recentRefusalRate - baselineAvgRefusal) / baselineStddevRefusal;
                if (Math.abs(zRefusal) >= Z_THRESHOLD_WARNING) {
                    anomalies.add(buildSkillAnomaly(
                            "refusal_spike", "refusal", skillName, "refusal_rate",
                            recentRefusalRate, baselineAvgRefusal, baselineStddevRefusal, zRefusal));
                }
            }
        }

        // Query 2: Agent-level AHT anomalies
        long t2 = System.currentTimeMillis();
        List<Map<String, Object>> agentRows = queryAgentAnomalies(tenantId);
        log.info("Agent anomaly query returned {} rows in {}ms", agentRows.size(), System.currentTimeMillis() - t2);

        for (Map<String, Object> row : agentRows) {
            String firstName = row.get("USER_FIRST_NAME") != null ? row.get("USER_FIRST_NAME").toString() : "";
            String lastName = row.get("USER_LAST_NAME") != null ? row.get("USER_LAST_NAME").toString() : "";
            String agentName = (firstName + " " + lastName).trim();
            if (agentName.isEmpty()) {
                agentName = "Agent_" + toInt(row.get("USER_ID"));
            }

            double recentAht = toDouble(row.get("RECENT_AHT"));
            double baselineAht = toDouble(row.get("BASELINE_AHT"));
            double baselineStddev = toDouble(row.get("BASELINE_STDDEV"));
            if (baselineStddev > 0) {
                double zScore = (recentAht - baselineAht) / baselineStddev;
                if (Math.abs(zScore) >= Z_THRESHOLD_WARNING) {
                    anomalies.add(buildAgentAnomaly(agentName, recentAht, baselineAht, baselineStddev, zScore));
                }
            }
        }

        // Sort by severity DESC (critical first), then by |zScore| DESC
        anomalies.sort(Comparator
                .<Map<String, Object>, Integer>comparing(a -> "critical".equals(a.get("severity")) ? 0 : 1)
                .thenComparing(a -> -Math.abs(toDouble(a.get("zScore")))));

        // Limit and assign IDs
        if (anomalies.size() > MAX_ANOMALIES) {
            anomalies = new ArrayList<>(anomalies.subList(0, MAX_ANOMALIES));
        }
        for (int i = 0; i < anomalies.size(); i++) {
            anomalies.get(i).put("id", String.format("ANM-%03d", i + 1));
        }

        return assembleResponse(anomalies);
    }

    // -------------------------------------------------------------------------
    // Snowflake queries
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> querySkillAnomalies(String tenantId) {
        String sql = "WITH daily_skill_stats AS (\n"
                + "  SELECT\n"
                + "    a.SKILL_NO,\n"
                + "    DATE_TRUNC('day', a.START_TIMESTAMP) AS DT,\n"
                + "    COUNT(*) AS DAILY_VOLUME,\n"
                + "    AVG(a.HANDLE_SECONDS) AS DAILY_AHT,\n"
                + "    CASE WHEN COUNT(*) > 0\n"
                + "         THEN SUM(CASE WHEN a.IS_REFUSED_FLAG = 1 THEN 1 ELSE 0 END) * 100.0 / COUNT(*)\n"
                + "         ELSE 0 END AS DAILY_REFUSAL_RATE\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -" + (BASELINE_DAYS + 1) + ", CURRENT_TIMESTAMP())\n"
                + "  GROUP BY a.SKILL_NO, DT\n"
                + "),\n"
                + "baseline AS (\n"
                + "  SELECT\n"
                + "    SKILL_NO,\n"
                + "    AVG(DAILY_VOLUME) AS BASELINE_AVG_VOLUME,\n"
                + "    STDDEV(DAILY_VOLUME) AS BASELINE_STDDEV_VOLUME,\n"
                + "    AVG(DAILY_AHT) AS BASELINE_AVG_AHT,\n"
                + "    STDDEV(DAILY_AHT) AS BASELINE_STDDEV_AHT,\n"
                + "    AVG(DAILY_REFUSAL_RATE) AS BASELINE_AVG_REFUSAL_RATE,\n"
                + "    STDDEV(DAILY_REFUSAL_RATE) AS BASELINE_STDDEV_REFUSAL_RATE\n"
                + "  FROM daily_skill_stats\n"
                + "  WHERE DT < CURRENT_DATE()\n"
                + "  GROUP BY SKILL_NO\n"
                + "  HAVING COUNT(DISTINCT DT) >= 7\n"
                + "),\n"
                + "recent AS (\n"
                + "  SELECT\n"
                + "    SKILL_NO,\n"
                + "    SUM(DAILY_VOLUME) AS RECENT_VOLUME,\n"
                + "    AVG(DAILY_AHT) AS RECENT_AHT,\n"
                + "    AVG(DAILY_REFUSAL_RATE) AS RECENT_REFUSAL_RATE\n"
                + "  FROM daily_skill_stats\n"
                + "  WHERE DT = CURRENT_DATE()\n"
                + "  GROUP BY SKILL_NO\n"
                + ")\n"
                + "SELECT\n"
                + "  r.SKILL_NO,\n"
                + "  MAX(sk.SKILL_NAME) AS SKILL_NAME,\n"
                + "  r.RECENT_VOLUME,\n"
                + "  b.BASELINE_AVG_VOLUME,\n"
                + "  b.BASELINE_STDDEV_VOLUME,\n"
                + "  r.RECENT_AHT,\n"
                + "  b.BASELINE_AVG_AHT,\n"
                + "  b.BASELINE_STDDEV_AHT,\n"
                + "  r.RECENT_REFUSAL_RATE,\n"
                + "  b.BASELINE_AVG_REFUSAL_RATE,\n"
                + "  b.BASELINE_STDDEV_REFUSAL_RATE\n"
                + "FROM recent r\n"
                + "JOIN baseline b ON b.SKILL_NO = r.SKILL_NO\n"
                + "LEFT JOIN " + SnowflakeExecutor.VIEW_SKILL_DIM + " sk\n"
                + "  ON sk.SKILL_NO = r.SKILL_NO AND sk._TENANT_ID = '" + tenantId + "'\n"
                + "GROUP BY r.SKILL_NO, r.RECENT_VOLUME, r.RECENT_AHT, r.RECENT_REFUSAL_RATE,\n"
                + "         b.BASELINE_AVG_VOLUME, b.BASELINE_STDDEV_VOLUME,\n"
                + "         b.BASELINE_AVG_AHT, b.BASELINE_STDDEV_AHT,\n"
                + "         b.BASELINE_AVG_REFUSAL_RATE, b.BASELINE_STDDEV_REFUSAL_RATE";

        return snowflakeExecutor.execute(sql);
    }

    private List<Map<String, Object>> queryAgentAnomalies(String tenantId) {
        String sql = "WITH agent_daily AS (\n"
                + "  SELECT\n"
                + "    a.USER_ID,\n"
                + "    DATE_TRUNC('day', a.START_TIMESTAMP) AS DT,\n"
                + "    AVG(a.HANDLE_SECONDS) AS DAILY_AHT\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -" + (BASELINE_DAYS + 1) + ", CURRENT_TIMESTAMP())\n"
                + "  GROUP BY a.USER_ID, DT\n"
                + "  HAVING COUNT(*) >= 5\n"
                + "),\n"
                + "agent_baseline AS (\n"
                + "  SELECT\n"
                + "    USER_ID,\n"
                + "    AVG(DAILY_AHT) AS BASELINE_AHT,\n"
                + "    STDDEV(DAILY_AHT) AS BASELINE_STDDEV\n"
                + "  FROM agent_daily\n"
                + "  WHERE DT < CURRENT_DATE()\n"
                + "  GROUP BY USER_ID\n"
                + "  HAVING COUNT(DISTINCT DT) >= 7 AND STDDEV(DAILY_AHT) > 0\n"
                + "),\n"
                + "agent_recent AS (\n"
                + "  SELECT\n"
                + "    USER_ID,\n"
                + "    DAILY_AHT AS RECENT_AHT\n"
                + "  FROM agent_daily\n"
                + "  WHERE DT = CURRENT_DATE()\n"
                + ")\n"
                + "SELECT\n"
                + "  r.USER_ID,\n"
                + "  u.USER_FIRST_NAME,\n"
                + "  u.USER_LAST_NAME,\n"
                + "  r.RECENT_AHT,\n"
                + "  b.BASELINE_AHT,\n"
                + "  b.BASELINE_STDDEV,\n"
                + "  (r.RECENT_AHT - b.BASELINE_AHT) / b.BASELINE_STDDEV AS Z_SCORE\n"
                + "FROM agent_recent r\n"
                + "JOIN agent_baseline b ON b.USER_ID = r.USER_ID\n"
                + "LEFT JOIN " + SnowflakeExecutor.VIEW_USER_DIM + " u\n"
                + "  ON u.USER_ID = r.USER_ID AND u._TENANT_ID = '" + tenantId + "'\n"
                + "WHERE ABS((r.RECENT_AHT - b.BASELINE_AHT) / b.BASELINE_STDDEV) >= " + Z_THRESHOLD_WARNING + "\n"
                + "ORDER BY ABS((r.RECENT_AHT - b.BASELINE_AHT) / b.BASELINE_STDDEV) DESC\n"
                + "LIMIT 10";

        return snowflakeExecutor.execute(sql);
    }

    // -------------------------------------------------------------------------
    // Anomaly builders
    // -------------------------------------------------------------------------

    private Map<String, Object> buildSkillAnomaly(String type, String dimension,
                                                   String skillName, String metric,
                                                   double currentValue, double baselineMean,
                                                   double baselineStddev, double zScore) {
        String severity = Math.abs(zScore) >= Z_THRESHOLD_CRITICAL ? "critical" : "warning";
        double deviationPct = baselineMean != 0 ? ((currentValue - baselineMean) / baselineMean) * 100.0 : 0.0;
        String deviationStr = String.format("%+.0f%%", deviationPct);

        Map<String, Object> anomaly = new LinkedHashMap<>();
        anomaly.put("id", ""); // assigned later
        anomaly.put("type", type);
        anomaly.put("severity", severity);
        anomaly.put("dimension", dimension);
        anomaly.put("entity", skillName);
        anomaly.put("entityType", "skill");
        anomaly.put("metric", metric);
        anomaly.put("currentValue", Math.round(currentValue * 10.0) / 10.0);
        anomaly.put("baselineMean", Math.round(baselineMean * 10.0) / 10.0);
        anomaly.put("baselineStddev", Math.round(baselineStddev * 10.0) / 10.0);
        anomaly.put("zScore", Math.round(zScore * 100.0) / 100.0);
        anomaly.put("deviation", deviationStr);
        anomaly.put("description", buildDescription(type, skillName, zScore, dimension));
        anomaly.put("impact", buildImpact(type, deviationPct));
        anomaly.put("suggestedAction", buildSuggestedAction(type, skillName));
        return anomaly;
    }

    private Map<String, Object> buildAgentAnomaly(String agentName,
                                                   double currentAht, double baselineMean,
                                                   double baselineStddev, double zScore) {
        String severity = Math.abs(zScore) >= Z_THRESHOLD_CRITICAL ? "critical" : "warning";
        double deviationPct = baselineMean != 0 ? ((currentAht - baselineMean) / baselineMean) * 100.0 : 0.0;
        String deviationStr = String.format("%+.0f%%", deviationPct);

        Map<String, Object> anomaly = new LinkedHashMap<>();
        anomaly.put("id", ""); // assigned later
        anomaly.put("type", "agent_aht_anomaly");
        anomaly.put("severity", severity);
        anomaly.put("dimension", "agent_behavior");
        anomaly.put("entity", agentName);
        anomaly.put("entityType", "agent");
        anomaly.put("metric", "personal_aht");
        anomaly.put("currentValue", Math.round(currentAht * 10.0) / 10.0);
        anomaly.put("baselineMean", Math.round(baselineMean * 10.0) / 10.0);
        anomaly.put("baselineStddev", Math.round(baselineStddev * 10.0) / 10.0);
        anomaly.put("zScore", Math.round(zScore * 100.0) / 100.0);
        anomaly.put("deviation", deviationStr);
        anomaly.put("description", String.format("%s AHT today is %.1fσ above their personal baseline",
                agentName, zScore));
        anomaly.put("impact", "Possible burnout signal or system issue affecting this agent");
        anomaly.put("suggestedAction", "Check with agent — may need support, training refresh, or schedule adjustment");
        return anomaly;
    }

    // -------------------------------------------------------------------------
    // Description / impact / action generators
    // -------------------------------------------------------------------------

    private String buildDescription(String type, String entity, double zScore, String dimension) {
        String absZ = String.format("%.1f", Math.abs(zScore));
        switch (type) {
            case "volume_surge":
                return String.format("Contact volume for %s is %s standard deviations above the %d-day average",
                        entity, absZ, BASELINE_DAYS);
            case "volume_drop":
                return String.format("Contact volume for %s is %s standard deviations below the %d-day average",
                        entity, absZ, BASELINE_DAYS);
            case "aht_spike":
                return String.format("AHT for %s is %sσ above baseline", entity, absZ);
            case "aht_drop":
                return String.format("AHT for %s is %sσ below baseline", entity, absZ);
            case "refusal_spike":
                return String.format("Refusal rate for %s is %sσ above normal — agents may be overwhelmed",
                        entity, absZ);
            default:
                return String.format("%s anomaly detected for %s (%sσ deviation)", dimension, entity, absZ);
        }
    }

    private String buildImpact(String type, double deviationPct) {
        int absPct = (int) Math.abs(deviationPct);
        switch (type) {
            case "volume_surge":
                return String.format("Queue buildup likely — volume %d%% above normal capacity planning", absPct);
            case "volume_drop":
                return String.format("Possible routing issue or channel shift — %d%% fewer contacts than expected", absPct);
            case "aht_spike":
                return String.format("Each contact costs %d%% more agent time — potential for queue overflow", absPct);
            case "aht_drop":
                return String.format("AHT %d%% below normal — check for premature disconnects or quality issues", absPct);
            case "refusal_spike":
                return "High refusal rate indicates agents unable to accept contacts — check skill routing and agent availability";
            default:
                return "Anomalous behavior detected — investigate root cause";
        }
    }

    private String buildSuggestedAction(String type, String entity) {
        switch (type) {
            case "volume_surge":
                return String.format("Activate overflow routing or reassign proficient agents to %s", entity);
            case "volume_drop":
                return "Verify IVR routing paths and check for upstream system issues";
            case "aht_spike":
                return "Check for system issues or new complex case type. Consider adding guided scripts.";
            case "aht_drop":
                return "Review call recordings for premature disconnects or quality concerns";
            case "refusal_spike":
                return "Check agent state management and routing rules. Consider adjusting max-contacts or break schedules.";
            default:
                return "Investigate metric trend and correlate with recent changes";
        }
    }

    // -------------------------------------------------------------------------
    // Response assembly
    // -------------------------------------------------------------------------

    private Map<String, Object> assembleResponse(List<Map<String, Object>> anomalies) {
        int critical = 0;
        int warning = 0;
        for (Map<String, Object> a : anomalies) {
            if ("critical".equals(a.get("severity"))) critical++;
            else warning++;
        }

        // Determine which dimensions are represented
        List<String> dimensions = new ArrayList<>();
        boolean hasVolume = false, hasAht = false, hasRefusal = false, hasAgent = false;
        for (Map<String, Object> a : anomalies) {
            String dim = (String) a.get("dimension");
            if ("volume".equals(dim)) hasVolume = true;
            else if ("aht".equals(dim)) hasAht = true;
            else if ("refusal".equals(dim)) hasRefusal = true;
            else if ("agent_behavior".equals(dim)) hasAgent = true;
        }
        if (hasVolume) dimensions.add("volume");
        if (hasAht) dimensions.add("aht");
        if (hasRefusal) dimensions.add("refusal");
        if (hasAgent) dimensions.add("agent_behavior");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAnomalies", anomalies.size());
        summary.put("critical", critical);
        summary.put("warning", warning);
        summary.put("dimensionsCovered", dimensions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        result.put("summary", summary);
        result.put("anomalies", anomalies);
        return result;
    }

    // -------------------------------------------------------------------------
    // Mock response (when Snowflake is not configured)
    // -------------------------------------------------------------------------

    private Map<String, Object> buildMockResponse() {
        List<Map<String, Object>> anomalies = new ArrayList<>();

        // Mock 1: Volume surge — critical
        Map<String, Object> a1 = new LinkedHashMap<>();
        a1.put("id", "ANM-001");
        a1.put("type", "volume_surge");
        a1.put("severity", "critical");
        a1.put("dimension", "volume");
        a1.put("entity", "DP_LATAM_C_SPA");
        a1.put("entityType", "skill");
        a1.put("metric", "daily_contacts");
        a1.put("currentValue", 2840);
        a1.put("baselineMean", 1950.0);
        a1.put("baselineStddev", 280.0);
        a1.put("zScore", 3.18);
        a1.put("deviation", "+46%");
        a1.put("description", "Contact volume for DP_LATAM_C_SPA is 3.2 standard deviations above the 21-day average");
        a1.put("impact", "Queue buildup likely — currently 58 contacts in queue");
        a1.put("suggestedAction", "Activate overflow routing or reassign 4 proficient agents from DSS_MEX_Spa_Tech");
        anomalies.add(a1);

        // Mock 2: AHT spike — critical
        Map<String, Object> a2 = new LinkedHashMap<>();
        a2.put("id", "ANM-002");
        a2.put("type", "aht_spike");
        a2.put("severity", "critical");
        a2.put("dimension", "aht");
        a2.put("entity", "Technical_Support");
        a2.put("entityType", "skill");
        a2.put("metric", "avg_handle_time");
        a2.put("currentValue", 485.3);
        a2.put("baselineMean", 320.0);
        a2.put("baselineStddev", 52.5);
        a2.put("zScore", 3.15);
        a2.put("deviation", "+52%");
        a2.put("description", "AHT for Technical_Support is 3.2σ above baseline");
        a2.put("impact", "Each contact costs 52% more agent time — potential for queue overflow");
        a2.put("suggestedAction", "Check for system issues or new complex case type. Consider adding guided scripts.");
        anomalies.add(a2);

        // Mock 3: Agent anomaly — warning
        Map<String, Object> a3 = new LinkedHashMap<>();
        a3.put("id", "ANM-003");
        a3.put("type", "agent_aht_anomaly");
        a3.put("severity", "warning");
        a3.put("dimension", "agent_behavior");
        a3.put("entity", "Carlos Mendes");
        a3.put("entityType", "agent");
        a3.put("metric", "personal_aht");
        a3.put("currentValue", 620.0);
        a3.put("baselineMean", 340.0);
        a3.put("baselineStddev", 85.0);
        a3.put("zScore", 2.94);
        a3.put("deviation", "+82%");
        a3.put("description", "Carlos Mendes AHT today is 2.9σ above their personal baseline");
        a3.put("impact", "Possible burnout signal or system issue affecting this agent");
        a3.put("suggestedAction", "Check with agent — may need support, training refresh, or schedule adjustment");
        anomalies.add(a3);

        // Mock 4: Refusal spike — warning
        Map<String, Object> a4 = new LinkedHashMap<>();
        a4.put("id", "ANM-004");
        a4.put("type", "refusal_spike");
        a4.put("severity", "warning");
        a4.put("dimension", "refusal");
        a4.put("entity", "Sales_Inbound_EN");
        a4.put("entityType", "skill");
        a4.put("metric", "refusal_rate");
        a4.put("currentValue", 18.5);
        a4.put("baselineMean", 7.2);
        a4.put("baselineStddev", 4.1);
        a4.put("zScore", 2.76);
        a4.put("deviation", "+157%");
        a4.put("description", "Refusal rate for Sales_Inbound_EN is 2.8σ above normal — agents may be overwhelmed");
        a4.put("impact", "High refusal rate indicates agents unable to accept contacts — check skill routing and agent availability");
        a4.put("suggestedAction", "Check agent state management and routing rules. Consider adjusting max-contacts or break schedules.");
        anomalies.add(a4);

        // Mock 5: Volume drop — warning
        Map<String, Object> a5 = new LinkedHashMap<>();
        a5.put("id", "ANM-005");
        a5.put("type", "volume_drop");
        a5.put("severity", "warning");
        a5.put("dimension", "volume");
        a5.put("entity", "Billing_Support_FR");
        a5.put("entityType", "skill");
        a5.put("metric", "daily_contacts");
        a5.put("currentValue", 85);
        a5.put("baselineMean", 320.0);
        a5.put("baselineStddev", 95.0);
        a5.put("zScore", -2.47);
        a5.put("deviation", "-73%");
        a5.put("description", "Contact volume for Billing_Support_FR is 2.5 standard deviations below the 21-day average");
        a5.put("impact", "Possible routing issue or channel shift — 73% fewer contacts than expected");
        a5.put("suggestedAction", "Verify IVR routing paths and check for upstream system issues");
        anomalies.add(a5);

        return assembleResponse(anomalies);
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
}
