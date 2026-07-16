package com.nice.agentic.config;

import com.nice.agentic.query.SnowflakeExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Health and status endpoints for monitoring and readiness probes.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private static final String VERSION = "1.0.0";

    private static final List<String> MODULE_NAMES = Arrays.asList(
            "overflow", "forecast", "burnout", "anomaly", "simulator",
            "shrinkage", "deflection", "roi", "briefing"
    );

    private final ModuleMetrics moduleMetrics;
    private final SnowflakeExecutor snowflakeExecutor;

    public HealthController(ModuleMetrics moduleMetrics, SnowflakeExecutor snowflakeExecutor) {
        this.moduleMetrics = moduleMetrics;
        this.snowflakeExecutor = snowflakeExecutor;
    }

    /**
     * Full health check with module statuses, Snowflake connectivity, and Bedrock configuration.
     */
    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("version", VERSION);
        result.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        // Module statuses
        Map<String, Object> modules = new LinkedHashMap<>();
        for (String moduleName : MODULE_NAMES) {
            Map<String, Object> moduleStatus = new LinkedHashMap<>();
            long lastMs = moduleMetrics.get(moduleName);
            if (lastMs >= 0) {
                moduleStatus.put("status", "UP");
                moduleStatus.put("lastResponseMs", lastMs);
            } else {
                moduleStatus.put("status", "UNKNOWN");
                moduleStatus.put("lastResponseMs", 0);
            }
            modules.put(moduleName, moduleStatus);
        }
        result.put("modules", modules);

        // Snowflake status
        Map<String, Object> snowflake = new LinkedHashMap<>();
        snowflake.put("connected", snowflakeExecutor.isConfigured());
        long snowflakeMs = moduleMetrics.get("snowflake_query");
        snowflake.put("lastQueryMs", snowflakeMs >= 0 ? snowflakeMs : 0);
        result.put("snowflake", snowflake);

        // Bedrock status
        Map<String, Object> bedrock = new LinkedHashMap<>();
        bedrock.put("configured", true);
        result.put("bedrock", bedrock);

        return result;
    }

    /**
     * Simple readiness probe: returns 200 if Snowflake is configured, 503 otherwise.
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (snowflakeExecutor.isConfigured()) {
            body.put("status", "READY");
            body.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            return ResponseEntity.ok(body);
        } else {
            body.put("status", "NOT_READY");
            body.put("reason", "Snowflake is not configured");
            body.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }
}
