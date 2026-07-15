package com.nice.agentic.widget;

import com.nice.agentic.TenantContext;
import com.nice.agentic.query.OpenSearchExecutor;
import com.nice.agentic.query.SnowflakeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Central resolver for all widget payloads used by the agentic-mvp tools.
 *
 * <p>Each widget has a registered {@link WidgetSpec}. Calling {@link #resolve(String, Map)}
 * routes to a private method that either queries a live data source or returns realistic
 * stub data marked with PLACEHOLDER comments.</p>
 *
 * <p>The {@code queue_state} widget optionally reads live queue depth from Valkey via
 * {@link ValkeyWidgetClient}; all other widgets currently use stubs.</p>
 *
 * <p>All stub data tells a coherent story for the Banking AHT demo scenario:
 * AHT spiked at 11:00 when a batch of 14 new-hire agents was added to the Banking skill.
 * The 0-30 day tenure cohort handles calls at ~2x the veteran baseline.</p>
 */
@Service
public class WidgetPayloadResolver {

    private static final Logger log = LoggerFactory.getLogger(WidgetPayloadResolver.class);

    /** Registry of all supported widget specs keyed by widgetId. */
    private static final Map<String, WidgetSpec> WIDGET_REGISTRY = Map.of(
        "aht_summary",
            new WidgetSpec("aht_summary",
                "Current live metric value vs baseline for a scope",
                List.of("scope", "metric")),
        "metric_history",
            new WidgetSpec("metric_history",
                "Hourly time-series for a metric over a date range",
                List.of("scope", "metric", "range")),
        "agent_leaderboard",
            new WidgetSpec("agent_leaderboard",
                "Agent performance sliced by a dimension (e.g. tenure)",
                List.of("scope", "dimension")),
        "realtime_staffing",
            new WidgetSpec("realtime_staffing",
                "Real-time staffing snapshot: agents on shift, available, in-call",
                List.of("scope")),
        "contact_volume",
            new WidgetSpec("contact_volume",
                "Contact volume breakdown, hourly, for a scope and time range",
                List.of("scope", "timeRange")),
        "queue_state",
            new WidgetSpec("queue_state",
                "Current queue depth, longest wait, and SLA compliance status",
                List.of("scope"))
    );

    private final ValkeyWidgetClient valkeyClient;
    private final SnowflakeExecutor snowflakeExecutor;
    private final OpenSearchExecutor openSearchExecutor;
    private final TenantContext tenantContext;

    public WidgetPayloadResolver(ValkeyWidgetClient valkeyClient,
                                 SnowflakeExecutor snowflakeExecutor,
                                 OpenSearchExecutor openSearchExecutor,
                                 TenantContext tenantContext) {
        this.valkeyClient = valkeyClient;
        this.snowflakeExecutor = snowflakeExecutor;
        this.openSearchExecutor = openSearchExecutor;
        this.tenantContext = tenantContext;
    }

    /**
     * Resolve a widget payload.
     *
     * @param widgetId widget identifier — must be registered in {@link #WIDGET_REGISTRY}
     * @param args     resolver arguments (scope, metric, range, …)
     * @return resolved payload as a plain Map ready for JSON serialisation
     * @throws IllegalArgumentException if widgetId is not registered
     */
    public Map<String, Object> resolve(String widgetId, Map<String, String> args) {
        WidgetSpec spec = WIDGET_REGISTRY.get(widgetId);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown widget ID: '" + widgetId + "'");
        }

        long start = System.currentTimeMillis();
        Map<String, Object> payload = switch (widgetId) {
            case "aht_summary"       -> resolveAhtSummary(args);
            case "metric_history"    -> resolveMetricHistory(args);
            case "agent_leaderboard" -> resolveAgentLeaderboard(args);
            case "realtime_staffing" -> resolveRealtimeStaffing(args);
            case "contact_volume"    -> resolveContactVolume(args);
            case "queue_state"       -> resolveQueueState(args);
            default -> throw new IllegalArgumentException("Unhandled widget: " + widgetId);
        };
        long elapsed = System.currentTimeMillis() - start;
        log.info("widget {} resolved for scope={} in {}ms",
                widgetId, args.getOrDefault("scope", "n/a"), elapsed);
        return payload;
    }

    // -------------------------------------------------------------------------
    // aht_summary — reads from Snowflake (historical AHT aggregate)
    // -------------------------------------------------------------------------

    private Map<String, Object> resolveAhtSummary(Map<String, String> args) {
        String scope  = args.getOrDefault("scope", "");
        String metric = args.getOrDefault("metric", "");

        if (snowflakeExecutor.isConfigured()) {
            try {
                String sql = String.format(
                    "SELECT skill_name, " +
                    "AVG(handle_seconds) AS avg_aht, " +
                    "COUNT(*) AS sample_size " +
                    "FROM %s " +
                    "WHERE _tenant_id = '%s' " +
                    "AND skill_name = '%s' " +
                    "AND agent_contact_start_timestamp >= DATEADD(hour, -4, CURRENT_TIMESTAMP()) " +
                    "GROUP BY skill_name",
                    SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT,
                    tenantContext.getTenantId(),
                    scope.replace("'", "''"));
                List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
                if (!rows.isEmpty()) {
                    Map<String, Object> row = rows.get(0);
                    double currentAht = ((Number) row.getOrDefault("AVG_AHT", 0)).doubleValue();
                    int sampleSize = ((Number) row.getOrDefault("SAMPLE_SIZE", 0)).intValue();
                    double baseline = 305.0;
                    double deltaPct = ((currentAht - baseline) / baseline) * 100;
                    return Map.of(
                        "scope", scope, "metric", metric,
                        "currentSeconds", (int) currentAht,
                        "baselineSeconds", (int) baseline,
                        "deltaPct", Math.round(deltaPct * 10.0) / 10.0,
                        "sampleSize", sampleSize,
                        "timestamp", Instant.now().toString(),
                        "source", "snowflake"
                    );
                }
            } catch (Exception e) {
                log.warn("Snowflake AHT query failed ({}), using stub", e.getMessage());
            }
        }

        // Stub fallback
        if ("Banking".equalsIgnoreCase(scope) && "AHT".equalsIgnoreCase(metric)) {
            return Map.of(
                "scope", "Banking", "metric", "AHT",
                "currentSeconds", 412, "baselineSeconds", 305,
                "deltaPct", 35.1, "sampleSize", 1247,
                "timestamp", Instant.now().toString(), "source", "stub"
            );
        }
        return Map.of(
            "scope", scope, "metric", metric,
            "currentSeconds", 300, "baselineSeconds", 305,
            "deltaPct", -1.6, "sampleSize", 900,
            "timestamp", Instant.now().toString(), "source", "stub"
        );
    }

    // -------------------------------------------------------------------------
    // metric_history — reads hourly aggregates from Snowflake
    // -------------------------------------------------------------------------

    private Map<String, Object> resolveMetricHistory(Map<String, String> args) {
        String scope  = args.getOrDefault("scope", "");
        String metric = args.getOrDefault("metric", "");
        String range  = args.getOrDefault("range", "today");

        if (snowflakeExecutor.isConfigured()) {
            try {
                String sql = String.format(
                    "SELECT DATE_TRUNC('hour', TO_TIMESTAMP(agent_contact_start_timestamp/1000)) AS hour_bucket, " +
                    "AVG(handle_seconds) AS avg_aht, " +
                    "COUNT(*) AS contact_count " +
                    "FROM %s " +
                    "WHERE _tenant_id = '%s' " +
                    "AND skill_name = '%s' " +
                    "AND agent_contact_start_timestamp >= DATEADD(hour, -8, CURRENT_TIMESTAMP()) " +
                    "GROUP BY hour_bucket ORDER BY hour_bucket",
                    SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT,
                    tenantContext.getTenantId(),
                    scope.replace("'", "''"));
                List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
                if (!rows.isEmpty()) {
                    List<Map<String, Object>> hourly = rows.stream()
                        .map(r -> Map.<String, Object>of(
                            "hour", String.valueOf(r.getOrDefault("HOUR_BUCKET", "")),
                            "value", ((Number) r.getOrDefault("AVG_AHT", 0)).intValue(),
                            "contacts", ((Number) r.getOrDefault("CONTACT_COUNT", 0)).intValue()))
                        .collect(Collectors.toList());
                    return Map.of("scope", scope, "metric", metric, "range", range,
                            "hourly", hourly, "source", "snowflake");
                }
            } catch (Exception e) {
                log.warn("Snowflake metric_history query failed ({}), using stub", e.getMessage());
            }
        }

        return Map.of(
            "scope", scope, "metric", metric, "range", range,
            "hourly", List.of(
                Map.of("hour", "09:00", "value", 308),
                Map.of("hour", "10:00", "value", 315),
                Map.of("hour", "11:00", "value", 402),
                Map.of("hour", "12:00", "value", 431),
                Map.of("hour", "13:00", "value", 412)
            ),
            "note", "Sharp step-up at 11:00, sustained since",
            "source", "stub"
        );
    }

    // -------------------------------------------------------------------------
    // agent_leaderboard — reads from Snowflake (historical, read-only)
    // -------------------------------------------------------------------------

    private Map<String, Object> resolveAgentLeaderboard(Map<String, String> args) {
        String scope     = args.getOrDefault("scope", "");
        String dimension = args.getOrDefault("dimension", "");

        if ("tenure".equalsIgnoreCase(dimension) && snowflakeExecutor.isConfigured()) {
            try {
                String sql = String.format(
                    "SELECT " +
                    "  CASE " +
                    "    WHEN DATEDIFF(day, a.hire_date, CURRENT_DATE()) <= 30 THEN '0-30 days' " +
                    "    WHEN DATEDIFF(day, a.hire_date, CURRENT_DATE()) <= 180 THEN '30-180 days' " +
                    "    ELSE '180+ days' " +
                    "  END AS tenure_bucket, " +
                    "  AVG(acf.handle_seconds) AS avg_aht, " +
                    "  COUNT(DISTINCT acf.agent_no) AS agent_count, " +
                    "  COUNT(*) AS contacts_handled " +
                    "FROM %s acf " +
                    "JOIN %s a ON acf.agent_no = a.agent_no AND acf._tenant_id = a._tenant_id " +
                    "WHERE acf._tenant_id = '%s' " +
                    "AND acf.skill_name = '%s' " +
                    "AND acf.agent_contact_start_timestamp >= DATEADD(hour, -8, CURRENT_TIMESTAMP()) " +
                    "GROUP BY tenure_bucket ORDER BY avg_aht DESC",
                    SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT,
                    SnowflakeExecutor.VIEW_AGENT_DIM,
                    tenantContext.getTenantId(),
                    scope.replace("'", "''"));
                List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
                if (!rows.isEmpty()) {
                    List<Map<String, Object>> groups = rows.stream()
                        .map(r -> Map.<String, Object>of(
                            "bucket", String.valueOf(r.getOrDefault("TENURE_BUCKET", "unknown")),
                            "ahtSeconds", ((Number) r.getOrDefault("AVG_AHT", 0)).intValue(),
                            "agentsOnShift", ((Number) r.getOrDefault("AGENT_COUNT", 0)).intValue(),
                            "contactsHandled", ((Number) r.getOrDefault("CONTACTS_HANDLED", 0)).intValue()))
                        .collect(Collectors.toList());
                    return Map.of("scope", scope, "dimension", dimension,
                            "groups", groups, "source", "snowflake");
                }
            } catch (Exception e) {
                log.warn("Snowflake agent_leaderboard query failed ({}), using stub", e.getMessage());
            }
        }

        // Stub fallback
        if ("tenure".equalsIgnoreCase(dimension)) {
            return Map.of(
                "scope", scope, "dimension", dimension,
                "groups", List.of(
                    Map.of("bucket", "0-30 days", "ahtSeconds", 578, "agentsOnShift", 14, "contactsHandled", 189),
                    Map.of("bucket", "30-180 days", "ahtSeconds", 402, "agentsOnShift", 9, "contactsHandled", 156),
                    Map.of("bucket", "180+ days", "ahtSeconds", 289, "agentsOnShift", 21, "contactsHandled", 902)
                ),
                "note", "0-30 day cohort is ~2x baseline AHT and started shift at 11:00",
                "source", "stub"
            );
        }
        return Map.of(
            "scope", scope, "dimension", dimension,
            "groups", List.of(),
            "note", "No slice data available for dimension: " + dimension,
            "source", "stub"
        );
    }

    // -------------------------------------------------------------------------
    // realtime_staffing — reads from Valkey (real-time)
    // -------------------------------------------------------------------------

    private Map<String, Object> resolveRealtimeStaffing(Map<String, String> args) {
        String scope = args.getOrDefault("scope", "");

        if (valkeyClient.isAvailable()) {
            String prefix = "staffing:" + scope + ":";
            int onShift   = valkeyClient.getInt(prefix + "onShift").orElse(44);
            int available = valkeyClient.getInt(prefix + "available").orElse(38);
            int inCall    = valkeyClient.getInt(prefix + "inCall").orElse(29);
            int newHires  = valkeyClient.getInt(prefix + "newHireCount").orElse(0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scope", scope);
            result.put("agentsOnShift", onShift);
            result.put("agentsAvailable", available);
            result.put("agentsInCall", inCall);
            result.put("newHireCount", newHires);
            result.put("scheduledCount", onShift + 6);
            result.put("source", "valkey");
            if (newHires > 0) {
                result.put("newHireStartTime", "11:00");
                result.put("note", newHires + " new-hire agents added at 11:00 today");
            }
            return result;
        }

        return Map.of(
            "scope", scope,
            "agentsOnShift", 44, "agentsAvailable", 38,
            "agentsInCall", 29, "newHireCount", 14,
            "newHireStartTime", "11:00", "scheduledCount", 50,
            "note", "14 new-hire agents added at 11:00 today",
            "source", "stub"
        );
    }

    // -------------------------------------------------------------------------
    // contact_volume — reads from OpenSearch (NRT) for recent, Snowflake for historical
    // -------------------------------------------------------------------------

    private Map<String, Object> resolveContactVolume(Map<String, String> args) {
        String scope     = args.getOrDefault("scope", "");
        String timeRange = args.getOrDefault("timeRange", "today");

        // Try OpenSearch first (real-time contacts indexed by nrt-cache)
        if (openSearchExecutor.isConfigured()) {
            try {
                Map<String, String> filters = Map.of(
                        "tenantId", tenantContext.getTenantId(),
                        "skillName", scope);
                List<Map<String, Object>> hits = openSearchExecutor.search(
                        "agent-contact-write-alias", filters, 500);
                if (!hits.isEmpty()) {
                    int total = hits.size();
                    // Group by hour for breakdown
                    Map<String, Long> byHour = hits.stream()
                        .filter(h -> h.containsKey("agentContactStartTimestamp"))
                        .collect(Collectors.groupingBy(
                            h -> {
                                long ts = ((Number) h.get("agentContactStartTimestamp")).longValue();
                                Instant t = Instant.ofEpochMilli(ts);
                                return String.format("%02d:00", t.atZone(java.time.ZoneId.systemDefault()).getHour());
                            },
                            Collectors.counting()));
                    List<Map<String, Object>> hourly = byHour.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> Map.<String, Object>of("hour", e.getKey(), "contacts", e.getValue().intValue()))
                        .collect(Collectors.toList());
                    return Map.of("scope", scope, "timeRange", timeRange,
                            "totalContacts", total, "hourly", hourly, "source", "opensearch");
                }
            } catch (Exception e) {
                log.warn("OpenSearch contact_volume query failed ({}), trying Snowflake", e.getMessage());
            }
        }

        // Fall back to Snowflake historical
        if (snowflakeExecutor.isConfigured()) {
            try {
                String sql = String.format(
                    "SELECT DATE_TRUNC('hour', TO_TIMESTAMP(agent_contact_start_timestamp/1000)) AS hour_bucket, " +
                    "COUNT(*) AS contact_count " +
                    "FROM %s " +
                    "WHERE _tenant_id = '%s' " +
                    "AND skill_name = '%s' " +
                    "AND agent_contact_start_timestamp >= DATEADD(hour, -8, CURRENT_TIMESTAMP()) " +
                    "GROUP BY hour_bucket ORDER BY hour_bucket",
                    SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT,
                    tenantContext.getTenantId(),
                    scope.replace("'", "''"));
                List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
                if (!rows.isEmpty()) {
                    int total = rows.stream()
                        .mapToInt(r -> ((Number) r.getOrDefault("CONTACT_COUNT", 0)).intValue()).sum();
                    List<Map<String, Object>> hourly = rows.stream()
                        .map(r -> Map.<String, Object>of(
                            "hour", String.valueOf(r.getOrDefault("HOUR_BUCKET", "")),
                            "contacts", ((Number) r.getOrDefault("CONTACT_COUNT", 0)).intValue()))
                        .collect(Collectors.toList());
                    return Map.of("scope", scope, "timeRange", timeRange,
                            "totalContacts", total, "hourly", hourly, "source", "snowflake");
                }
            } catch (Exception e) {
                log.warn("Snowflake contact_volume query failed ({}), using stub", e.getMessage());
            }
        }

        return Map.of(
            "scope", scope, "timeRange", timeRange,
            "totalContacts", 1247,
            "hourly", List.of(
                Map.of("hour", "09:00", "contacts", 187),
                Map.of("hour", "10:00", "contacts", 195),
                Map.of("hour", "11:00", "contacts", 251),
                Map.of("hour", "12:00", "contacts", 312),
                Map.of("hour", "13:00", "contacts", 302)
            ),
            "note", "Volume spike at 11:00 coincides with new-hire shift start",
            "source", "stub"
        );
    }

    // -------------------------------------------------------------------------
    // queue_state — reads live queue depth + SLA from Valkey
    // -------------------------------------------------------------------------

    private Map<String, Object> resolveQueueState(Map<String, String> args) {
        String scope = args.getOrDefault("scope", "");

        String depthKey = "queue:depth:" + scope;
        String waitKey  = "queue:wait:"  + scope;
        String slaKey   = "queue:sla:"   + scope;

        int    queueDepth         = 23;
        int    longestWaitSeconds = 187;
        double slaCompliance      = 0.82;
        String source             = "stub";

        if (valkeyClient.isAvailable()) {
            queueDepth         = valkeyClient.getInt(depthKey).orElse(queueDepth);
            longestWaitSeconds = valkeyClient.getInt(waitKey).orElse(longestWaitSeconds);
            slaCompliance      = valkeyClient.get(slaKey)
                    .map(v -> { try { return Double.parseDouble(v); } catch (Exception e) { return 0.82; } })
                    .orElse(0.82);
            source = "valkey";
        }

        String status = slaCompliance >= 0.95 ? "healthy"
                      : slaCompliance >= 0.85 ? "warning"
                      : "at-risk";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope);
        payload.put("queueDepth", queueDepth);
        payload.put("longestWaitSeconds", longestWaitSeconds);
        payload.put("slaCompliance", slaCompliance);
        payload.put("forecastedSlaCompliance", Math.max(0, slaCompliance - 0.03));
        payload.put("status", status);
        payload.put("source", source);
        payload.put("note", String.format("Current SLA %.0f%%, %s", slaCompliance * 100, status));
        return payload;
    }
}
