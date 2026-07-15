package com.nice.agentic.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GET /admin/refresh-schema — queries Snowflake INFORMATION_SCHEMA to discover
 * real column names and saves them to src/main/resources/schema-columns.json.
 * Run once after schema changes — no need to run at every startup.
 */
@RestController
public class SchemaRefreshController {

    private static final Logger log = LoggerFactory.getLogger(SchemaRefreshController.class);

    private final SnowflakeExecutor snowflakeExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Map<String, String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("DATAHUB.ENTITYMGT_REFINED.AGENT_SESSION_ACTIVITY_FACT", "ENTITYMGT_REFINED|AGENT_SESSION_ACTIVITY_FACT");
        TABLES.put("DATAHUB.SUITE_REFINED.AGENT_CONTACT_FACT_VIEW_V011", "SUITE_REFINED|AGENT_CONTACT_FACT_VIEW_V011");
        TABLES.put("DATAHUB.SUITE_REFINED.USER_DIM_VIEW_V001", "SUITE_REFINED|USER_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.AGENT_STATE_DIM_VIEW_V011", "SUITE_REFINED|AGENT_STATE_DIM_VIEW_V011");
        TABLES.put("DATAHUB.SUITE_REFINED.SKILL_SCD_DIM_VIEW_V001", "SUITE_REFINED|SKILL_SCD_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.CHANNEL_DIM_VIEW_V001", "SUITE_REFINED|CHANNEL_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.CONTACT_STATE_DIM_VIEW_V001", "SUITE_REFINED|CONTACT_STATE_DIM_VIEW_V001");
        TABLES.put("DATAHUB.TM_REFINED.TENANT_DIM_VIEW_V001", "TM_REFINED|TENANT_DIM_VIEW_V001");
    }

    public SchemaRefreshController(SnowflakeExecutor snowflakeExecutor) {
        this.snowflakeExecutor = snowflakeExecutor;
    }

    @GetMapping("/admin/refresh-schema")
    public Map<String, Object> refreshSchema() throws IOException {
        if (!snowflakeExecutor.isConfigured()) {
            return Map.of("error", "Snowflake not configured");
        }

        Map<String, List<String>> result = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : TABLES.entrySet()) {
            String fullName = entry.getKey();
            String[] parts = entry.getValue().split("\\|");
            String schema = parts[0];
            String table = parts[1];

            try {
                String sql = "SELECT COLUMN_NAME FROM DATAHUB.INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = '" + schema + "' AND TABLE_NAME = '" + table + "' " +
                        "ORDER BY ORDINAL_POSITION";
                List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
                List<String> columns = rows.stream()
                        .map(r -> String.valueOf(r.get("COLUMN_NAME")))
                        .collect(Collectors.toList());
                result.put(fullName, columns);
                log.info("Discovered {}: {} columns", fullName, columns.size());
            } catch (Exception e) {
                log.warn("Failed to discover {}: {}", fullName, e.getMessage());
                result.put(fullName, List.of("ERROR: " + e.getMessage()));
            }
        }

        // Save to resources
        File outputFile = new File("src/main/resources/schema-columns.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, result);
        log.info("Schema saved to {}", outputFile.getAbsolutePath());

        return Map.of("status", "ok", "tables", result.size(), "file", outputFile.getAbsolutePath());
    }
}
