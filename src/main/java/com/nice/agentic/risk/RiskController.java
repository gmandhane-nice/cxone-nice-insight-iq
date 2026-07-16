package com.nice.agentic.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.TenantContext;
import com.nice.agentic.query.SnowflakeExecutor;
import com.nice.agentic.widget.WidgetPayloadResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/risk")
public class RiskController {

    private static final Logger log = LoggerFactory.getLogger(RiskController.class);

    private final ObjectMapper objectMapper;
    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;

    /**
     * Injected lazily so the application still starts if Agent B's bean is not yet registered.
     */
    private final WidgetPayloadResolver widgetPayloadResolver;

    private Map<String, Object> snapshotTemplate;

    public RiskController(ObjectMapper objectMapper,
                          SnowflakeExecutor snowflakeExecutor,
                          TenantContext tenantContext,
                          @Lazy WidgetPayloadResolver widgetPayloadResolver) {
        this.objectMapper = objectMapper;
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
        this.widgetPayloadResolver = widgetPayloadResolver;
    }

    @PostConstruct
    public void loadSnapshot() {
        try {
            ClassPathResource resource = new ClassPathResource("fixtures/risk-snapshot.json");
            //noinspection unchecked
            snapshotTemplate = objectMapper.readValue(
                    resource.getInputStream(), Map.class);
            log.info("Loaded risk-snapshot.json with {} skills",
                    ((List<?>) snapshotTemplate.getOrDefault("skills", List.of())).size());
        } catch (IOException e) {
            log.error("Failed to load risk-snapshot.json — using empty snapshot: {}", e.getMessage());
            snapshotTemplate = new HashMap<>();
        }
    }

    // -------------------------------------------------------------------------
    // GET /risk/snapshot
    // -------------------------------------------------------------------------

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        // If Snowflake is configured, attempt to build a live risk snapshot
        if (snowflakeExecutor.isConfigured()) {
            try {
                Map<String, Object> liveSnapshot = buildLiveRiskSnapshot();
                if (liveSnapshot != null && !((List<?>) liveSnapshot.get("skills")).isEmpty()) {
                    log.info("Returning live risk snapshot with {} skills",
                            ((List<?>) liveSnapshot.get("skills")).size());
                    return liveSnapshot;
                }
                log.warn("Live risk snapshot returned no skills — falling back to static data");
            } catch (Exception e) {
                log.error("Failed to build live risk snapshot — falling back to static data: {}", e.getMessage(), e);
            }
        }

        // Fallback: return static fixture data (original behavior)
        Map<String, Object> result = deepCopySnapshot(snapshotTemplate);

        // Attempt to enrich the Banking skill with a live queue depth
        try {
            Map<String, Object> liveQueueState = widgetPayloadResolver.resolve(
                    "queue_state", Map.of("scope", "Banking"));

            Object liveDepth = liveQueueState.get("queueDepth");
            if (liveDepth != null) {
                enrichBankingSkill(result, liveDepth);
                log.info("Enriched Banking skill queueDepth with live value: {}", liveDepth);
            }
        } catch (Exception e) {
            log.warn("Could not enrich snapshot with live queue state (returning static data): {}", e.getMessage());
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Live Snowflake risk snapshot
    // -------------------------------------------------------------------------

    private Map<String, Object> buildLiveRiskSnapshot() {
        String tenantId = tenantContext.getTenantId();

        // Step 1: Get contacts per skill with SLA metrics (try last 1 hour, widen if empty)
        List<Map<String, Object>> contactMetrics = queryContactMetrics(tenantId, 1);
        if (contactMetrics.isEmpty()) {
            log.info("No contacts found in last 1 hour — widening to last 720 days for demo data");
            contactMetrics = queryContactMetrics(tenantId, 720 * 24);
        }

        if (contactMetrics.isEmpty()) {
            return null;
        }

        // Step 2: Get queue depth (contacts with no END_TIMESTAMP)
        Map<Integer, Integer> queueDepthBySkill = queryQueueDepth(tenantId);

        // Step 3: Get agents available per skill (last hour, widen if needed)
        Map<Integer, Integer> agentsBySkill = queryAgentsAvailable(tenantId, 1);
        if (agentsBySkill.isEmpty()) {
            agentsBySkill = queryAgentsAvailable(tenantId, 720 * 24);
        }

        // Step 4: Build the response — skip null/unnamed skills
        List<Map<String, Object>> skills = new ArrayList<>();
        for (Map<String, Object> row : contactMetrics) {
            Object skillName = row.get("SKILL_NAME");
            if (skillName == null || skillName.toString().isBlank()) continue;
            Map<String, Object> skill = buildSkillEntry(row, queueDepthBySkill, agentsBySkill);
            skills.add(skill);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        result.put("skills", skills);
        return result;
    }

    private List<Map<String, Object>> queryContactMetrics(String tenantId, int hoursBack) {
        String sql = "WITH recent_contacts AS (\n"
                + "  SELECT a.SKILL_NO, a.AGENT_CONTACT_DURATION_SECONDS, a.CHANNEL_NO\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(hour, -" + hoursBack + ", CURRENT_TIMESTAMP())\n"
                + ")\n"
                + "SELECT sk.SKILL_NAME, sk.SKILL_NO,\n"
                + "  COUNT(*) AS CONTACT_COUNT,\n"
                + "  AVG(rc.AGENT_CONTACT_DURATION_SECONDS) AS AVG_HANDLE_TIME,\n"
                + "  SUM(CASE WHEN rc.AGENT_CONTACT_DURATION_SECONDS < 300 THEN 1 ELSE 0 END) * 1.0 / NULLIF(COUNT(*), 0) AS SLA_COMPLIANCE\n"
                + "FROM recent_contacts rc\n"
                + "LEFT JOIN " + SnowflakeExecutor.VIEW_SKILL_DIM + " sk\n"
                + "  ON rc.SKILL_NO = sk.SKILL_NO AND sk._TENANT_ID = '" + tenantId + "'\n"
                + "GROUP BY sk.SKILL_NAME, sk.SKILL_NO\n"
                + "ORDER BY SLA_COMPLIANCE ASC\n"
                + "LIMIT 20";

        return snowflakeExecutor.execute(sql);
    }

    private Map<Integer, Integer> queryQueueDepth(String tenantId) {
        String sql = "SELECT a.SKILL_NO, COUNT(*) AS QUEUE_DEPTH\n"
                + "FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "  AND a.END_TIMESTAMP IS NULL\n"
                + "  AND a.START_TIMESTAMP >= DATEADD(day, -7, CURRENT_TIMESTAMP())\n"
                + "GROUP BY a.SKILL_NO";

        Map<Integer, Integer> result = new HashMap<>();
        try {
            List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
            for (Map<String, Object> row : rows) {
                Integer skillNo = toInt(row.get("SKILL_NO"));
                Integer depth = toInt(row.get("QUEUE_DEPTH"));
                if (skillNo != null && depth != null) {
                    result.put(skillNo, depth);
                }
            }
        } catch (Exception e) {
            log.warn("Queue depth query failed: {}", e.getMessage());
        }
        return result;
    }

    private Map<Integer, Integer> queryAgentsAvailable(String tenantId, int hoursBack) {
        String sql = "SELECT a.SKILL_NO, COUNT(DISTINCT a.USER_ID) AS AGENT_COUNT\n"
                + "FROM " + SnowflakeExecutor.VIEW_AGENT_SESSION_FACT + " a\n"
                + "WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "  AND a.START_TIMESTAMP >= DATEADD(hour, -" + hoursBack + ", CURRENT_TIMESTAMP())\n"
                + "GROUP BY a.SKILL_NO";

        Map<Integer, Integer> result = new HashMap<>();
        try {
            List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
            for (Map<String, Object> row : rows) {
                Integer skillNo = toInt(row.get("SKILL_NO"));
                Integer count = toInt(row.get("AGENT_COUNT"));
                if (skillNo != null && count != null) {
                    result.put(skillNo, count);
                }
            }
        } catch (Exception e) {
            log.warn("Agents available query failed: {}", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> buildSkillEntry(Map<String, Object> contactRow,
                                                 Map<Integer, Integer> queueDepthBySkill,
                                                 Map<Integer, Integer> agentsBySkill) {
        Integer skillNo = toInt(contactRow.get("SKILL_NO"));
        String skillName = contactRow.get("SKILL_NAME") != null && !contactRow.get("SKILL_NAME").toString().isBlank()
                ? contactRow.get("SKILL_NAME").toString()
                : "Unassigned";

        double slaCompliance = toDouble(contactRow.get("SLA_COMPLIANCE"), 1.0);
        double avgHandleTime = toDouble(contactRow.get("AVG_HANDLE_TIME"), 0.0);
        int contactCount = toInt(contactRow.get("CONTACT_COUNT")) != null
                ? toInt(contactRow.get("CONTACT_COUNT")) : 0;

        int queueDepth = queueDepthBySkill.getOrDefault(skillNo, 0);
        int agentsAvailable = agentsBySkill.getOrDefault(skillNo, 0);

        // Calculate risk level
        String riskLevel;
        if (slaCompliance < 0.85) {
            riskLevel = "at-risk";
        } else if (slaCompliance < 0.90) {
            riskLevel = "warning";
        } else {
            riskLevel = "healthy";
        }

        // Forecast SLA: slight decay based on queue depth pressure
        double forecastedSla = slaCompliance;
        if (queueDepth > 0 && agentsAvailable > 0) {
            double pressureRatio = (double) queueDepth / agentsAvailable;
            if (pressureRatio > 0.5) {
                forecastedSla = Math.max(0.0, slaCompliance - (pressureRatio * 0.03));
            }
        }

        // Estimate agents required based on contact load
        int agentsRequired = agentsAvailable;
        if ("at-risk".equals(riskLevel) || "warning".equals(riskLevel)) {
            // Need roughly 20% more agents if at-risk, 10% more if warning
            double multiplier = "at-risk".equals(riskLevel) ? 1.2 : 1.1;
            agentsRequired = Math.max(agentsAvailable, (int) Math.ceil(agentsAvailable * multiplier));
        }

        // Build recommendation
        Map<String, Object> recommendation = null;
        if ("at-risk".equals(riskLevel)) {
            int overflowPct = Math.min(30, Math.max(10, (int) ((0.85 - slaCompliance) * 200)));
            recommendation = new LinkedHashMap<>();
            recommendation.put("action", "overflow");
            recommendation.put("overflowTo", "General-Support");
            recommendation.put("overflowPct", overflowPct);
            recommendation.put("durationMinutes", 30);
            recommendation.put("predictedSlaImpact", "+" + overflowPct / 4 + "%");
            recommendation.put("predictedSlaAfterAction",
                    Math.round((slaCompliance + overflowPct * 0.0025) * 100.0) / 100.0);
        } else if ("warning".equals(riskLevel)) {
            recommendation = new LinkedHashMap<>();
            recommendation.put("action", "monitor");
            recommendation.put("overflowTo", null);
            recommendation.put("overflowPct", 0);
            recommendation.put("durationMinutes", 0);
            recommendation.put("predictedSlaImpact", "0%");
            recommendation.put("predictedSlaAfterAction", Math.round(forecastedSla * 100.0) / 100.0);
        }

        // Build the skill map
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("skillId", "SKL-" + skillNo);
        skill.put("skillName", skillName);
        skill.put("riskLevel", riskLevel);
        skill.put("currentSlaCompliance", Math.round(slaCompliance * 100.0) / 100.0);
        skill.put("forecastedSlaCompliance", Math.round(forecastedSla * 100.0) / 100.0);
        skill.put("queueDepth", queueDepth);
        skill.put("agentsAvailable", agentsAvailable);
        skill.put("agentsRequired", agentsRequired);
        skill.put("recommendation", recommendation);
        skill.put("contactCount", contactCount);
        skill.put("avgHandleTime", Math.round(avgHandleTime * 10.0) / 10.0);

        return skill;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void enrichBankingSkill(Map<String, Object> snapshot, Object liveQueueDepth) {
        Object skillsRaw = snapshot.get("skills");
        if (!(skillsRaw instanceof List<?> skills)) return;

        for (Object skillObj : skills) {
            if (!(skillObj instanceof Map<?, ?> skill)) continue;
            Object name = skill.get("skillName");
            if ("Banking".equals(name)) {
                ((Map<String, Object>) skill).put("queueDepth", liveQueueDepth);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopySnapshot(Map<String, Object> source) {
        try {
            // Serialize + deserialize for a clean deep copy
            byte[] bytes = objectMapper.writeValueAsBytes(source);
            return objectMapper.readValue(bytes, Map.class);
        } catch (IOException e) {
            log.warn("Deep copy of snapshot failed, returning original: {}", e.getMessage());
            return new HashMap<>(source);
        }
    }

    private static Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
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
