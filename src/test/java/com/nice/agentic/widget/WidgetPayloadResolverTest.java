package com.nice.agentic.widget;

import com.nice.agentic.TenantContext;
import com.nice.agentic.query.OpenSearchExecutor;
import com.nice.agentic.query.SnowflakeExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WidgetPayloadResolver}.
 *
 * No Spring context is loaded. {@link ValkeyWidgetClient} is created directly —
 * the {@code @PostConstruct init()} is NOT called when instantiated outside Spring,
 * so {@code available} stays {@code false} and no real Valkey connection is attempted.
 * A private inner subclass simulates a "live" Valkey connection for the positive path.
 */
class WidgetPayloadResolverTest {

    /** Stub that pretends Valkey is NOT reachable — uses ValkeyWidgetClient's default state. */
    private WidgetPayloadResolver resolver;

    @BeforeEach
    void setUp() {
        ValkeyWidgetClient offlineValkey = new ValkeyWidgetClient("localhost", 6379);
        SnowflakeExecutor offlineSnowflake = new SnowflakeExecutor(
                "placeholder-account", "placeholder-user", "", "",
                "", "placeholder-warehouse", "DATAHUB", "placeholder-role", "SUITE_REFINED", 60);
        OpenSearchExecutor offlineOpenSearch = new OpenSearchExecutor("", "", "", true, 10, 5000);
        TenantContext tenantContext = new TenantContext("11efd95f-eed7-42e0-a6c9-0242ac110002");
        resolver = new WidgetPayloadResolver(offlineValkey, offlineSnowflake, offlineOpenSearch, tenantContext);
    }

    // -------------------------------------------------------------------------
    // aht_summary
    // -------------------------------------------------------------------------

    @Test
    void ahtSummary_returnsCurrentSeconds() {
        Map<String, Object> result = resolver.resolve(
                "aht_summary",
                Map.of("scope", "Banking", "metric", "AHT"));

        assertTrue(result.containsKey("currentSeconds"),
                "aht_summary payload must include 'currentSeconds'");
        assertEquals(412, result.get("currentSeconds"));
    }

    @Test
    void ahtSummary_returnsAllRequiredFields() {
        Map<String, Object> result = resolver.resolve(
                "aht_summary",
                Map.of("scope", "Banking", "metric", "AHT"));

        assertAll(
            () -> assertEquals("Banking", result.get("scope")),
            () -> assertEquals("AHT",     result.get("metric")),
            () -> assertTrue(result.containsKey("baselineSeconds")),
            () -> assertTrue(result.containsKey("deltaPct")),
            () -> assertTrue(result.containsKey("sampleSize")),
            () -> assertTrue(result.containsKey("timestamp"))
        );
    }

    // -------------------------------------------------------------------------
    // metric_history
    // -------------------------------------------------------------------------

    @Test
    void metricHistory_returnsHourlyList() {
        Map<String, Object> result = resolver.resolve(
                "metric_history",
                Map.of("scope", "Banking", "metric", "AHT", "range", "today"));

        assertTrue(result.containsKey("hourly"), "metric_history payload must include 'hourly'");
        assertTrue(result.get("hourly") instanceof java.util.List);
        assertFalse(((java.util.List<?>) result.get("hourly")).isEmpty());
    }

    // -------------------------------------------------------------------------
    // agent_leaderboard
    // -------------------------------------------------------------------------

    @Test
    void agentLeaderboard_tenureDimension_returnsThreeBuckets() {
        Map<String, Object> result = resolver.resolve(
                "agent_leaderboard",
                Map.of("scope", "Banking", "dimension", "tenure"));

        java.util.List<?> groups = (java.util.List<?>) result.get("groups");
        assertNotNull(groups);
        assertEquals(3, groups.size(), "tenure dimension should return 3 cohort buckets");
    }

    // -------------------------------------------------------------------------
    // realtime_staffing
    // -------------------------------------------------------------------------

    @Test
    void realtimeStaffing_returnsNewHireCount() {
        Map<String, Object> result = resolver.resolve(
                "realtime_staffing",
                Map.of("scope", "Banking"));

        assertTrue(result.containsKey("newHireCount"));
        assertEquals(14, result.get("newHireCount"));
    }

    // -------------------------------------------------------------------------
    // contact_volume
    // -------------------------------------------------------------------------

    @Test
    void contactVolume_returnsTotalContacts() {
        Map<String, Object> result = resolver.resolve(
                "contact_volume",
                Map.of("scope", "Banking", "timeRange", "today"));

        assertTrue(result.containsKey("totalContacts"));
        assertEquals(1247, result.get("totalContacts"));
    }

    // -------------------------------------------------------------------------
    // queue_state
    // -------------------------------------------------------------------------

    @Test
    void queueState_returnsAtRiskStatus() {
        Map<String, Object> result = resolver.resolve(
                "queue_state",
                Map.of("scope", "Banking"));

        assertEquals("at-risk", result.get("status"));
    }

    @Test
    void queueState_valkeyUnavailable_returnsStubSource() {
        Map<String, Object> result = resolver.resolve(
                "queue_state",
                Map.of("scope", "Banking"));

        assertEquals("stub", result.get("source"),
                "queue_state should fall back to stub when Valkey is unavailable");
    }

    @Test
    void queueState_valkeyAvailable_readsLiveDepth() {
        // Use an anonymous subclass to simulate a live Valkey response without a real connection
        ValkeyWidgetClient liveValkey = new ValkeyWidgetClient("localhost", 6379) {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<String> get(String key) {
                return switch (key) {
                    case "queue:sla:Banking" -> Optional.of("0.82");
                    default -> Optional.empty();
                };
            }
            @Override public Optional<Integer> getInt(String key) {
                return switch (key) {
                    case "queue:depth:Banking" -> Optional.of(31);
                    case "queue:wait:Banking"  -> Optional.of(202);
                    default                    -> Optional.empty();
                };
            }
        };

        SnowflakeExecutor offlineSf = new SnowflakeExecutor(
                "placeholder-account", "placeholder-user", "", "",
                "", "placeholder-warehouse", "DATAHUB", "placeholder-role", "SUITE_REFINED", 60);
        OpenSearchExecutor offlineOs = new OpenSearchExecutor("", "", "", true, 10, 5000);
        TenantContext tenantCtx = new TenantContext("11efd95f-eed7-42e0-a6c9-0242ac110002");
        WidgetPayloadResolver liveResolver = new WidgetPayloadResolver(liveValkey, offlineSf, offlineOs, tenantCtx);
        Map<String, Object> result = liveResolver.resolve(
                "queue_state", Map.of("scope", "Banking"));

        assertEquals(31,       result.get("queueDepth"),        "live queueDepth from Valkey");
        assertEquals(202,      result.get("longestWaitSeconds"), "live longestWait from Valkey");
        assertEquals("valkey", result.get("source"),             "source should be 'valkey'");
    }

    // -------------------------------------------------------------------------
    // Unknown widget
    // -------------------------------------------------------------------------

    @Test
    void resolve_unknownWidgetId_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("nonexistent_widget", Map.of("scope", "Banking")),
                "Resolving an unknown widgetId should throw IllegalArgumentException");
    }

    // -------------------------------------------------------------------------
    // All 6 widgets resolve without error
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "aht_summary",
        "metric_history",
        "agent_leaderboard",
        "realtime_staffing",
        "contact_volume",
        "queue_state"
    })
    void allWidgets_resolveWithoutError(String widgetId) {
        Map<String, String> args = Map.of(
            "scope",     "Banking",
            "metric",    "AHT",
            "range",     "today",
            "dimension", "tenure",
            "timeRange", "today"
        );

        Map<String, Object> result = assertDoesNotThrow(
                () -> resolver.resolve(widgetId, args),
                "Widget '" + widgetId + "' should resolve without throwing");

        assertNotNull(result, "Resolved payload must not be null for widget: " + widgetId);
        assertFalse(result.isEmpty(), "Resolved payload must not be empty for widget: " + widgetId);
    }
}
