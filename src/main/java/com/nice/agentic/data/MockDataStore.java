package com.nice.agentic.data;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Stands in for Valkey (real-time) + OpenSearch (historical).
 * Numbers are hard-coded to tell a coherent story for the "Banking AHT" demo scenario:
 * AHT is up because a new-hire cohort was routed to Banking today.
 */
@Component
public class MockDataStore {

    public Map<String, Object> metricSnapshot(String scope, String metric) {
        if ("Banking".equalsIgnoreCase(scope) && "AHT".equalsIgnoreCase(metric)) {
            return Map.of(
                    "scope", scope,
                    "metric", metric,
                    "currentSeconds", 412,
                    "baselineSeconds", 305,
                    "deltaPct", 35.1,
                    "sampleSize", 1247
            );
        }
        return Map.of(
                "scope", scope,
                "metric", metric,
                "currentSeconds", 300,
                "baselineSeconds", 305,
                "deltaPct", -1.6,
                "sampleSize", 900
        );
    }

    public Map<String, Object> metricHistory(String scope, String metric, String range) {
        return Map.of(
                "scope", scope,
                "metric", metric,
                "range", range,
                "hourly", List.of(
                        Map.of("hour", "09:00", "value", 308),
                        Map.of("hour", "10:00", "value", 315),
                        Map.of("hour", "11:00", "value", 402),
                        Map.of("hour", "12:00", "value", 431),
                        Map.of("hour", "13:00", "value", 412)
                ),
                "note", "Sharp step-up at 11:00, sustained since"
        );
    }

    public Map<String, Object> agentPerformanceSlice(String scope, String dimension) {
        if ("tenure".equalsIgnoreCase(dimension)) {
            return Map.of(
                    "scope", scope,
                    "dimension", dimension,
                    "groups", List.of(
                            Map.of("bucket", "0-30 days", "ahtSeconds", 578, "agentsOnShift", 14, "contactsHandled", 189),
                            Map.of("bucket", "30-180 days", "ahtSeconds", 402, "agentsOnShift", 9, "contactsHandled", 156),
                            Map.of("bucket", "180+ days", "ahtSeconds", 289, "agentsOnShift", 21, "contactsHandled", 902)
                    ),
                    "note", "0-30 day cohort is ~2x baseline AHT and started shift at 11:00"
            );
        }
        return Map.of(
                "scope", scope,
                "dimension", dimension,
                "groups", List.of(),
                "note", "No slice data for this dimension"
        );
    }
}
