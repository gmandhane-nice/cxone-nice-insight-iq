package com.nice.agentic.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton that tracks the last response time (in milliseconds) for each module.
 * Controllers call {@link #record(String, long)} after completing their main endpoint.
 */
@Component
public class ModuleMetrics {

    private final ConcurrentHashMap<String, Long> lastResponseMs = new ConcurrentHashMap<>();

    /**
     * Record the duration of a module's last response.
     *
     * @param moduleName logical name (e.g. "forecast", "anomaly")
     * @param durationMs elapsed time in milliseconds
     */
    public void record(String moduleName, long durationMs) {
        lastResponseMs.put(moduleName, durationMs);
    }

    /**
     * Returns an unmodifiable snapshot of all recorded module metrics.
     */
    public Map<String, Long> getAll() {
        return Map.copyOf(lastResponseMs);
    }

    /**
     * Returns the last recorded response time for a specific module, or -1 if never recorded.
     */
    public long get(String moduleName) {
        return lastResponseMs.getOrDefault(moduleName, -1L);
    }
}
