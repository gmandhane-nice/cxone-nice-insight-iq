package com.nice.agentic.briefing;

import com.nice.agentic.TenantContext;
import com.nice.agentic.config.ModuleMetrics;
import com.nice.agentic.query.SnowflakeExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supervisor AI Daily Briefing endpoint.
 *
 * <p>Generates a personalized morning briefing by aggregating signals across all
 * analytics modules (contact volume, skill SLAs, agent performance, queue state)
 * and using an LLM to synthesize them into a concise, actionable executive summary.</p>
 *
 * <p>This is the "wow factor" agentic feature — it shows how AI can cross-correlate
 * data from multiple sources and produce human-readable intelligence.</p>
 */
@RestController
@RequestMapping("/briefing")
@Tag(name = "Briefing")
public class DailyBriefingController {

    private static final Logger log = LoggerFactory.getLogger(DailyBriefingController.class);

    private static final String SYSTEM_PROMPT =
            "You are a senior contact center operations advisor generating a morning briefing for a supervisor. "
            + "Be concise, actionable, and data-driven. Structure your response as JSON with these fields: "
            + "headline (one impactful sentence), "
            + "priorities (array of 3-5 items, each with title + detail + urgency where urgency is one of: critical, high, medium, low), "
            + "wins (1-2 positive trends), "
            + "risks (1-2 emerging concerns), "
            + "recommendation (single most impactful action for today). "
            + "Return ONLY valid JSON, no markdown fencing.";

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;
    private final BedrockRuntimeClient bedrock;
    private final ModuleMetrics moduleMetrics;

    @Value("${agentic.bedrock.model-id}")
    private String modelId;

    public DailyBriefingController(SnowflakeExecutor snowflakeExecutor,
                                   TenantContext tenantContext,
                                   BedrockRuntimeClient bedrock,
                                   ModuleMetrics moduleMetrics) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
        this.bedrock = bedrock;
        this.moduleMetrics = moduleMetrics;
    }

    // -------------------------------------------------------------------------
    // GET /briefing/today
    // -------------------------------------------------------------------------

    @GetMapping("/today")
    @Operation(
            summary = "Get today's supervisor briefing",
            description = "Generates a personalized morning briefing by aggregating signals across all analytics modules "
                    + "and using an LLM to synthesize them into a concise, actionable executive summary. "
                    + "Includes priorities, wins, risks, and a top recommendation for the day.")
    public Map<String, Object> today() {
        long start = System.currentTimeMillis();

        try {
            if (!snowflakeExecutor.isConfigured()) {
                log.info("Snowflake not configured — returning mock daily briefing");
                Map<String, Object> mock = buildMockResponse();
                log.info("Daily briefing generated in {}ms (mock)", System.currentTimeMillis() - start);
                return mock;
            }

            try {
                Map<String, Object> result = buildLiveBriefing();
                log.info("Daily briefing generated in {}ms", System.currentTimeMillis() - start);
                return result;
            } catch (Exception e) {
                log.error("Failed to build live briefing — returning mock: {}", e.getMessage(), e);
                Map<String, Object> mock = buildMockResponse();
                log.info("Daily briefing generated in {}ms (fallback)", System.currentTimeMillis() - start);
                return mock;
            }
        } finally {
            moduleMetrics.record("briefing", System.currentTimeMillis() - start);
        }
    }

    // -------------------------------------------------------------------------
    // Live briefing from Snowflake + LLM
    // -------------------------------------------------------------------------

    private Map<String, Object> buildLiveBriefing() {
        String tenantId = tenantContext.getTenantId();

        // Single SQL with multiple CTEs to gather all briefing metrics
        String sql = buildBriefingSql(tenantId);
        List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);

        // Parse metrics from the result
        Map<String, Object> metrics = parseMetrics(rows);

        // Build the user prompt with all data for the LLM
        String userPrompt = buildUserPrompt(metrics);

        // Call the LLM to generate the briefing — fall back to template if it fails
        String llmBriefing;
        try {
            llmBriefing = callLlm(userPrompt);
        } catch (Exception e) {
            log.warn("LLM call failed, falling back to template-based briefing: {}", e.getMessage());
            return buildTemplateBasedBriefing(metrics);
        }

        // Assemble response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        // Strip markdown code fences if present
        String jsonText = llmBriefing;
        if (jsonText.startsWith("```")) {
            int firstNewline = jsonText.indexOf('\n');
            if (firstNewline >= 0) {
                jsonText = jsonText.substring(firstNewline + 1);
            }
            if (jsonText.endsWith("```")) {
                jsonText = jsonText.substring(0, jsonText.length() - 3).trim();
            }
        }

        // Try to parse LLM response as structured JSON
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> briefingJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(jsonText, LinkedHashMap.class);
            result.put("briefing", briefingJson);
        } catch (Exception e) {
            log.warn("LLM response was not valid JSON, wrapping as raw text: {}", e.getMessage());
            Map<String, Object> fallbackBriefing = new LinkedHashMap<>();
            fallbackBriefing.put("headline", "Daily briefing generated — see raw analysis below");
            fallbackBriefing.put("raw", llmBriefing);
            result.put("briefing", fallbackBriefing);
        }

        result.put("metrics", metrics);
        return result;
    }

    // -------------------------------------------------------------------------
    // SQL construction
    // -------------------------------------------------------------------------

    private String buildBriefingSql(String tenantId) {
        return "WITH yesterday_contacts AS (\n"
                + "  SELECT COUNT(*) AS TOTAL_CONTACTS,\n"
                + "         AVG(a.HANDLE_SECONDS) AS AVG_AHT,\n"
                + "         COUNT(DISTINCT a.USER_ID) AS ACTIVE_AGENTS\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP::DATE = CURRENT_DATE - 1\n"
                + "),\n"
                + "last_week_same_day AS (\n"
                + "  SELECT COUNT(*) AS TOTAL_CONTACTS\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP::DATE = CURRENT_DATE - 8\n"
                + "),\n"
                + "top_skills AS (\n"
                + "  SELECT MAX(s.SKILL_NAME) AS SKILL_NAME, COUNT(*) AS CONTACT_COUNT\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  JOIN " + SnowflakeExecutor.VIEW_SKILL_DIM + " s\n"
                + "    ON a.SKILL_NO = s.SKILL_NO AND s._TENANT_ID = '" + tenantId + "'\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP::DATE = CURRENT_DATE - 1\n"
                + "  GROUP BY a.SKILL_NO\n"
                + "  ORDER BY CONTACT_COUNT DESC\n"
                + "  LIMIT 3\n"
                + "),\n"
                + "sla_issues AS (\n"
                + "  SELECT MAX(s.SKILL_NAME) AS SKILL_NAME,\n"
                + "         AVG(a.HANDLE_SECONDS) AS AVG_AHT,\n"
                + "         SUM(CASE WHEN a.IS_REFUSED_FLAG = 1 THEN 1 ELSE 0 END) AS REFUSALS,\n"
                + "         COUNT(*) AS TOTAL\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  JOIN " + SnowflakeExecutor.VIEW_SKILL_DIM + " s\n"
                + "    ON a.SKILL_NO = s.SKILL_NO AND s._TENANT_ID = '" + tenantId + "'\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP::DATE = CURRENT_DATE - 1\n"
                + "  GROUP BY a.SKILL_NO\n"
                + "  HAVING AVG(a.HANDLE_SECONDS) > 400 OR (SUM(CASE WHEN a.IS_REFUSED_FLAG = 1 THEN 1 ELSE 0 END) * 100.0 / COUNT(*)) > 15\n"
                + "),\n"
                + "agent_aht_wow AS (\n"
                + "  SELECT u.USER_FIRST_NAME || ' ' || u.USER_LAST_NAME AS FULL_NAME,\n"
                + "         AVG(CASE WHEN a.START_TIMESTAMP::DATE = CURRENT_DATE - 1 THEN a.HANDLE_SECONDS END) AS YESTERDAY_AHT,\n"
                + "         AVG(CASE WHEN a.START_TIMESTAMP::DATE = CURRENT_DATE - 8 THEN a.HANDLE_SECONDS END) AS LAST_WEEK_AHT\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  JOIN " + SnowflakeExecutor.VIEW_USER_DIM + " u\n"
                + "    ON a.USER_ID = u.USER_ID AND u._TENANT_ID = '" + tenantId + "'\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP::DATE IN (CURRENT_DATE - 1, CURRENT_DATE - 8)\n"
                + "  GROUP BY u.USER_FIRST_NAME || ' ' || u.USER_LAST_NAME\n"
                + "  HAVING AVG(CASE WHEN a.START_TIMESTAMP::DATE = CURRENT_DATE - 1 THEN a.HANDLE_SECONDS END) IS NOT NULL\n"
                + "    AND AVG(CASE WHEN a.START_TIMESTAMP::DATE = CURRENT_DATE - 8 THEN a.HANDLE_SECONDS END) IS NOT NULL\n"
                + "  ORDER BY (AVG(CASE WHEN a.START_TIMESTAMP::DATE = CURRENT_DATE - 1 THEN a.HANDLE_SECONDS END)\n"
                + "          - AVG(CASE WHEN a.START_TIMESTAMP::DATE = CURRENT_DATE - 8 THEN a.HANDLE_SECONDS END)) DESC\n"
                + "  LIMIT 3\n"
                + "),\n"
                + "queue_depth AS (\n"
                + "  SELECT MAX(s.SKILL_NAME) AS SKILL_NAME, COUNT(*) AS IN_QUEUE\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  JOIN " + SnowflakeExecutor.VIEW_SKILL_DIM + " s\n"
                + "    ON a.SKILL_NO = s.SKILL_NO AND s._TENANT_ID = '" + tenantId + "'\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.END_TIMESTAMP IS NULL\n"
                + "  GROUP BY a.SKILL_NO\n"
                + ")\n"
                + "SELECT 'SUMMARY' AS SECTION,\n"
                + "       yc.TOTAL_CONTACTS AS YESTERDAY_CONTACTS,\n"
                + "       lw.TOTAL_CONTACTS AS LAST_WEEK_CONTACTS,\n"
                + "       yc.AVG_AHT AS AVG_AHT,\n"
                + "       yc.ACTIVE_AGENTS AS ACTIVE_AGENTS,\n"
                + "       NULL AS SKILL_NAME, NULL AS CONTACT_COUNT, NULL AS REFUSALS,\n"
                + "       NULL AS FULL_NAME, NULL AS YESTERDAY_AHT, NULL AS LAST_WEEK_AHT,\n"
                + "       NULL AS IN_QUEUE\n"
                + "FROM yesterday_contacts yc, last_week_same_day lw\n"
                + "UNION ALL\n"
                + "SELECT 'TOP_SKILL', NULL, NULL, NULL, NULL,\n"
                + "       SKILL_NAME, CONTACT_COUNT, NULL, NULL, NULL, NULL, NULL\n"
                + "FROM top_skills\n"
                + "UNION ALL\n"
                + "SELECT 'SLA_ISSUE', NULL, NULL, AVG_AHT, NULL,\n"
                + "       SKILL_NAME, TOTAL, REFUSALS, NULL, NULL, NULL, NULL\n"
                + "FROM sla_issues\n"
                + "UNION ALL\n"
                + "SELECT 'AGENT_AHT', NULL, NULL, NULL, NULL,\n"
                + "       NULL, NULL, NULL, FULL_NAME, YESTERDAY_AHT, LAST_WEEK_AHT, NULL\n"
                + "FROM agent_aht_wow\n"
                + "UNION ALL\n"
                + "SELECT 'QUEUE', NULL, NULL, NULL, NULL,\n"
                + "       SKILL_NAME, NULL, NULL, NULL, NULL, NULL, IN_QUEUE\n"
                + "FROM queue_depth";
    }

    // -------------------------------------------------------------------------
    // Metrics parsing
    // -------------------------------------------------------------------------

    private Map<String, Object> parseMetrics(List<Map<String, Object>> rows) {
        Map<String, Object> metrics = new LinkedHashMap<>();

        int yesterdayContacts = 0;
        int lastWeekContacts = 0;
        double avgAht = 0;
        int activeAgents = 0;
        List<Map<String, Object>> topSkills = new ArrayList<>();
        List<Map<String, Object>> slaIssues = new ArrayList<>();
        List<Map<String, Object>> agentAhtChanges = new ArrayList<>();
        List<Map<String, Object>> queueDepths = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String section = String.valueOf(row.get("SECTION"));
            switch (section) {
                case "SUMMARY":
                    yesterdayContacts = toInt(row.get("YESTERDAY_CONTACTS"));
                    lastWeekContacts = toInt(row.get("LAST_WEEK_CONTACTS"));
                    avgAht = toDouble(row.get("AVG_AHT"));
                    activeAgents = toInt(row.get("ACTIVE_AGENTS"));
                    break;
                case "TOP_SKILL":
                    Map<String, Object> skill = new LinkedHashMap<>();
                    skill.put("skillName", row.get("SKILL_NAME"));
                    skill.put("contactCount", toInt(row.get("CONTACT_COUNT")));
                    topSkills.add(skill);
                    break;
                case "SLA_ISSUE":
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("skillName", row.get("SKILL_NAME"));
                    issue.put("avgAht", toDouble(row.get("AVG_AHT")));
                    issue.put("refusals", toInt(row.get("REFUSALS")));
                    issue.put("totalContacts", toInt(row.get("CONTACT_COUNT")));
                    slaIssues.add(issue);
                    break;
                case "AGENT_AHT":
                    Map<String, Object> agent = new LinkedHashMap<>();
                    agent.put("agentName", row.get("FULL_NAME"));
                    agent.put("yesterdayAht", toDouble(row.get("YESTERDAY_AHT")));
                    agent.put("lastWeekAht", toDouble(row.get("LAST_WEEK_AHT")));
                    double yAht = toDouble(row.get("YESTERDAY_AHT"));
                    double lwAht = toDouble(row.get("LAST_WEEK_AHT"));
                    double change = lwAht > 0 ? ((yAht - lwAht) / lwAht) * 100.0 : 0;
                    agent.put("changePercent", Math.round(change * 10.0) / 10.0);
                    agentAhtChanges.add(agent);
                    break;
                case "QUEUE":
                    Map<String, Object> queue = new LinkedHashMap<>();
                    queue.put("skillName", row.get("SKILL_NAME"));
                    queue.put("inQueue", toInt(row.get("IN_QUEUE")));
                    queueDepths.add(queue);
                    break;
                default:
                    break;
            }
        }

        // Compute week-over-week change
        String wowChange = "N/A";
        if (lastWeekContacts > 0) {
            double pctChange = ((double) (yesterdayContacts - lastWeekContacts) / lastWeekContacts) * 100.0;
            wowChange = (pctChange >= 0 ? "+" : "") + Math.round(pctChange) + "%";
        }

        // Format AHT nicely
        String avgAhtFormatted = String.format("%.1f min", avgAht / 60.0);

        metrics.put("yesterdayContacts", yesterdayContacts);
        metrics.put("weekOverWeekChange", wowChange);
        metrics.put("activeAgents", activeAgents);
        metrics.put("avgTeamAht", avgAhtFormatted);
        metrics.put("topSkillsByVolume", topSkills);
        metrics.put("skillsWithSlaIssues", slaIssues);
        metrics.put("agentsWithRisingAht", agentAhtChanges);
        metrics.put("currentQueueDepths", queueDepths);

        if (!topSkills.isEmpty()) {
            metrics.put("topSkillByVolume", topSkills.get(0).get("skillName"));
        }
        if (!slaIssues.isEmpty()) {
            metrics.put("lowestSlaSkill", slaIssues.get(0).get("skillName"));
        }

        return metrics;
    }

    // -------------------------------------------------------------------------
    // LLM call
    // -------------------------------------------------------------------------

    private String callLlm(String userPrompt) {
        try {
            ConverseRequest request = ConverseRequest.builder()
                    .modelId(modelId)
                    .system(SystemContentBlock.fromText(SYSTEM_PROMPT))
                    .messages(Message.builder()
                            .role(ConversationRole.USER)
                            .content(ContentBlock.fromText(userPrompt))
                            .build())
                    .inferenceConfig(InferenceConfiguration.builder()
                            .maxTokens(1024)
                            .temperature(0.4f)
                            .build())
                    .build();

            ConverseResponse response = bedrock.converse(request);
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : response.output().message().content()) {
                if (block.text() != null) {
                    sb.append(block.text());
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("LLM call failed for daily briefing: {}", e.getMessage(), e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private String buildUserPrompt(Map<String, Object> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a morning briefing for today based on yesterday's contact center data.\n\n");
        sb.append("=== KEY METRICS ===\n");
        sb.append("Yesterday's total contacts: ").append(metrics.get("yesterdayContacts")).append("\n");
        sb.append("Week-over-week change: ").append(metrics.get("weekOverWeekChange")).append("\n");
        sb.append("Active agents yesterday: ").append(metrics.get("activeAgents")).append("\n");
        sb.append("Average team AHT: ").append(metrics.get("avgTeamAht")).append("\n\n");

        sb.append("=== TOP SKILLS BY VOLUME ===\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topSkills = (List<Map<String, Object>>) metrics.get("topSkillsByVolume");
        if (topSkills != null) {
            for (Map<String, Object> s : topSkills) {
                sb.append("- ").append(s.get("skillName")).append(": ").append(s.get("contactCount")).append(" contacts\n");
            }
        }

        sb.append("\n=== SKILLS WITH SLA ISSUES (High AHT or High Refusal) ===\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slaIssues = (List<Map<String, Object>>) metrics.get("skillsWithSlaIssues");
        if (slaIssues != null && !slaIssues.isEmpty()) {
            for (Map<String, Object> s : slaIssues) {
                sb.append("- ").append(s.get("skillName"))
                  .append(": AHT=").append(String.format("%.0f", (double) s.get("avgAht"))).append("s")
                  .append(", Refusals=").append(s.get("refusals"))
                  .append("/").append(s.get("totalContacts")).append("\n");
            }
        } else {
            sb.append("- None identified\n");
        }

        sb.append("\n=== AGENTS WITH HIGHEST AHT INCREASE (Week-over-Week) ===\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) metrics.get("agentsWithRisingAht");
        if (agents != null && !agents.isEmpty()) {
            for (Map<String, Object> a : agents) {
                sb.append("- ").append(a.get("agentName"))
                  .append(": yesterday ").append(String.format("%.0f", (double) a.get("yesterdayAht"))).append("s")
                  .append(" vs last week ").append(String.format("%.0f", (double) a.get("lastWeekAht"))).append("s")
                  .append(" (+").append(a.get("changePercent")).append("%)\n");
            }
        } else {
            sb.append("- No significant increases detected\n");
        }

        sb.append("\n=== CURRENT QUEUE DEPTH (contacts still in progress) ===\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = (List<Map<String, Object>>) metrics.get("currentQueueDepths");
        if (queues != null && !queues.isEmpty()) {
            for (Map<String, Object> q : queues) {
                sb.append("- ").append(q.get("skillName")).append(": ").append(q.get("inQueue")).append(" in queue\n");
            }
        } else {
            sb.append("- All queues clear\n");
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Template-based fallback (when LLM fails but data is available)
    // -------------------------------------------------------------------------

    private Map<String, Object> buildTemplateBasedBriefing(Map<String, Object> metrics) {
        Map<String, Object> briefing = new LinkedHashMap<>();
        briefing.put("headline", String.format("Yesterday: %s contacts (%s vs last week) with %s active agents",
                metrics.get("yesterdayContacts"), metrics.get("weekOverWeekChange"), metrics.get("activeAgents")));

        List<Map<String, Object>> priorities = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slaIssues = (List<Map<String, Object>>) metrics.get("skillsWithSlaIssues");
        if (slaIssues != null) {
            for (Map<String, Object> issue : slaIssues) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("title", issue.get("skillName") + " has SLA concerns");
                p.put("detail", String.format("AHT=%.0fs with %s refusals out of %s contacts",
                        issue.get("avgAht"), issue.get("refusals"), issue.get("totalContacts")));
                p.put("urgency", "high");
                priorities.add(p);
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = (List<Map<String, Object>>) metrics.get("currentQueueDepths");
        if (queues != null) {
            for (Map<String, Object> q : queues) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("title", q.get("skillName") + " has queue buildup");
                p.put("detail", q.get("inQueue") + " contacts waiting in queue");
                p.put("urgency", "critical");
                priorities.add(p);
            }
        }

        if (priorities.isEmpty()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("title", "Review team performance metrics");
            p.put("detail", "Average AHT is " + metrics.get("avgTeamAht"));
            p.put("urgency", "medium");
            priorities.add(p);
        }

        briefing.put("priorities", priorities);
        briefing.put("wins", List.of("Data collection operational across all skills"));
        briefing.put("risks", List.of("Review agents with rising AHT for potential burnout"));
        briefing.put("recommendation", "Focus on skills with SLA issues and consider rebalancing queue assignments.");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        result.put("briefing", briefing);
        result.put("metrics", metrics);
        return result;
    }

    // -------------------------------------------------------------------------
    // Mock response (when Snowflake is not configured)
    // -------------------------------------------------------------------------

    private Map<String, Object> buildMockResponse() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        // Mock briefing
        Map<String, Object> briefing = new LinkedHashMap<>();
        briefing.put("headline",
                "Contact volume up 12% — Billing queue needs immediate attention with 3 agents at burnout risk");

        List<Map<String, Object>> priorities = new ArrayList<>();

        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("title", "Billing SLA at 73%");
        p1.put("detail", "Queue depth 24, avg wait 4.2min. Consider overflow to General.");
        p1.put("urgency", "critical");
        priorities.add(p1);

        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("title", "3 agents showing burnout signals");
        p2.put("detail", "Maria, David, Sarah have rising AHT + refusals this week.");
        p2.put("urgency", "high");
        priorities.add(p2);

        Map<String, Object> p3 = new LinkedHashMap<>();
        p3.put("title", "Portuguese queue surging +177%");
        p3.put("detail", "DP_LATAM_C_POR growing fast. 4 proficient agents available for reassignment.");
        p3.put("urgency", "high");
        priorities.add(p3);

        Map<String, Object> p4 = new LinkedHashMap<>();
        p4.put("title", "Training compliance gap");
        p4.put("detail", "12 agents overdue on Q3 compliance module. Deadline Friday.");
        p4.put("urgency", "medium");
        priorities.add(p4);

        briefing.put("priorities", priorities);

        briefing.put("wins", List.of(
                "Team AHT improved 8% vs last week",
                "Zero SLA breaches on Technical_Support for 3 consecutive days"
        ));

        briefing.put("risks", List.of(
                "Wednesday historically peaks at 15:00 — current staffing may be insufficient",
                "Agent attrition risk: 2 agents have 50%+ shrinkage this week"
        ));

        briefing.put("recommendation",
                "Reassign 3 agents from General_Support to Billing immediately — predicted to recover SLA from 73% to 86%.");

        result.put("briefing", briefing);

        // Mock metrics
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("yesterdayContacts", 4520);
        metrics.put("weekOverWeekChange", "+12%");
        metrics.put("activeAgents", 145);
        metrics.put("avgTeamAht", "4.2 min");
        metrics.put("topSkillByVolume", "DP_LATAM_C_SPA");
        metrics.put("lowestSlaSkill", "Billing_Support (73%)");

        metrics.put("topSkillsByVolume", List.of(
                Map.of("skillName", "DP_LATAM_C_SPA", "contactCount", 1245),
                Map.of("skillName", "General_Support", "contactCount", 987),
                Map.of("skillName", "Billing_Support", "contactCount", 834)
        ));

        metrics.put("skillsWithSlaIssues", List.of(
                Map.of("skillName", "Billing_Support", "avgAht", 452.0, "refusals", 47, "totalContacts", 834),
                Map.of("skillName", "DP_LATAM_C_POR", "avgAht", 510.0, "refusals", 23, "totalContacts", 156)
        ));

        metrics.put("agentsWithRisingAht", List.of(
                Map.of("agentName", "Maria Gonzalez", "yesterdayAht", 485.0, "lastWeekAht", 312.0, "changePercent", 55.4),
                Map.of("agentName", "David Chen", "yesterdayAht", 420.0, "lastWeekAht", 295.0, "changePercent", 42.4),
                Map.of("agentName", "Sarah Johnson", "yesterdayAht", 398.0, "lastWeekAht", 290.0, "changePercent", 37.2)
        ));

        metrics.put("currentQueueDepths", List.of(
                Map.of("skillName", "Billing_Support", "inQueue", 24),
                Map.of("skillName", "DP_LATAM_C_POR", "inQueue", 11)
        ));

        result.put("metrics", metrics);

        return result;
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
