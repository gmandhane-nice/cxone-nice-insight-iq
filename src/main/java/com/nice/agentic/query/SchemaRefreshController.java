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
        // Core fact tables
        TABLES.put("DATAHUB.ENTITYMGT_REFINED.AGENT_SESSION_ACTIVITY_FACT", "ENTITYMGT_REFINED|AGENT_SESSION_ACTIVITY_FACT");
        TABLES.put("DATAHUB.SUITE_REFINED.AGENT_CONTACT_FACT_VIEW_V011", "SUITE_REFINED|AGENT_CONTACT_FACT_VIEW_V011");
        TABLES.put("DATAHUB.SUITE_REFINED.AGENT_SESSION_FACT_VIEW_V011", "SUITE_REFINED|AGENT_SESSION_FACT_VIEW_V011");
        TABLES.put("DATAHUB.SUITE_REFINED.AGENT_CONTACT_INTERVAL_FACT_VIEW_V011", "SUITE_REFINED|AGENT_CONTACT_INTERVAL_FACT_VIEW_V011");
        TABLES.put("DATAHUB.SUITE_REFINED.AGENT_SESSION_INTERVAL_FACT_VIEW_V011", "SUITE_REFINED|AGENT_SESSION_INTERVAL_FACT_VIEW_V011");
        TABLES.put("DATAHUB.SUITE_REFINED.CONTACT_ACTIVITY_FACT_VIEW_V005", "SUITE_REFINED|CONTACT_ACTIVITY_FACT_VIEW_V005");
        TABLES.put("DATAHUB.SUITE_REFINED.SKILL_INTERVAL_FACT_VIEW_V011", "SUITE_REFINED|SKILL_INTERVAL_FACT_VIEW_V011");
        TABLES.put("DATAHUB.SUITE_REFINED.CONTACT_SKILL_FACT_VIEW_V005", "SUITE_REFINED|CONTACT_SKILL_FACT_VIEW_V005");
        TABLES.put("DATAHUB.SUITE_REFINED.CUSTOMER_CONTACT_FACT_VIEW_V011", "SUITE_REFINED|CUSTOMER_CONTACT_FACT_VIEW_V011");
        TABLES.put("DATAHUB.SUITE_REFINED.CONSOLIDATED_AGENT_ACTIVITY_FACT_VIEW_V001", "SUITE_REFINED|CONSOLIDATED_AGENT_ACTIVITY_FACT_VIEW_V001");
        // Dimension tables
        TABLES.put("DATAHUB.SUITE_REFINED.USER_DIM_VIEW_V001", "SUITE_REFINED|USER_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.AGENT_STATE_DIM_VIEW_V011", "SUITE_REFINED|AGENT_STATE_DIM_VIEW_V011");
        TABLES.put("DATAHUB.SUITE_REFINED.SKILL_SCD_DIM_VIEW_V001", "SUITE_REFINED|SKILL_SCD_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.CHANNEL_DIM_VIEW_V001", "SUITE_REFINED|CHANNEL_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.CONTACT_STATE_DIM_VIEW_V001", "SUITE_REFINED|CONTACT_STATE_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.AGENT_CONTACT_STATE_DIM_VIEW_V011", "SUITE_REFINED|AGENT_CONTACT_STATE_DIM_VIEW_V011");
        TABLES.put("DATAHUB.SUITE_REFINED.DIRECTION_DIM_VIEW_V001", "SUITE_REFINED|DIRECTION_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.DISPOSITION_DIM_VIEW_V001", "SUITE_REFINED|DISPOSITION_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.CAMPAIGN_SCD_DIM_VIEW_V001", "SUITE_REFINED|CAMPAIGN_SCD_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.AGENT_SKILL_ASSIGNMENT_VIEW_V001", "SUITE_REFINED|AGENT_SKILL_ASSIGNMENT_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.SLIM_AGENT_SCD_DIM_VIEW_V001", "SUITE_REFINED|SLIM_AGENT_SCD_DIM_VIEW_V001");
        TABLES.put("DATAHUB.SUITE_REFINED.AGENT_TYPE_DIM_VIEW_V001", "SUITE_REFINED|AGENT_TYPE_DIM_VIEW_V001");
        // WFM tables
        TABLES.put("DATAHUB.WFM_REFINED.ADHERENCE_DETAIL_FACT", "WFM_REFINED|ADHERENCE_DETAIL_FACT");
        TABLES.put("DATAHUB.WFM_REFINED.FORECAST_SKILL_GROUP_INTERVAL_FACT", "WFM_REFINED|FORECAST_SKILL_GROUP_INTERVAL_FACT");
        TABLES.put("DATAHUB.WFM_REFINED.INTRADAY_SKILL_GROUP_INSIGHT_FACT", "WFM_REFINED|INTRADAY_SKILL_GROUP_INSIGHT_FACT");
        TABLES.put("DATAHUB.WFM_REFINED.SCHEDULING_UNIT_DIM", "WFM_REFINED|SCHEDULING_UNIT_DIM");
        TABLES.put("DATAHUB.WFM_REFINED.SKILL_GROUP_DIM", "WFM_REFINED|SKILL_GROUP_DIM");
        TABLES.put("DATAHUB.WFM_REFINED.ACTIVITY_DIM", "WFM_REFINED|ACTIVITY_DIM");
        TABLES.put("DATAHUB.WFM_REFINED.ADHERENCE_CATEGORY_DIM", "WFM_REFINED|ADHERENCE_CATEGORY_DIM");
        TABLES.put("DATAHUB.WFM_REFINED.SCHEDULE_TIME_UTILIZATION_FACT", "WFM_REFINED|SCHEDULE_TIME_UTILIZATION_FACT");
        // Tenant
        TABLES.put("DATAHUB.TM_REFINED.TENANT_DIM_VIEW_V001", "TM_REFINED|TENANT_DIM_VIEW_V001");
    }

    private static final List<String> DISCOVERY_SCHEMAS = List.of(
            "SUITE_REFINED", "ENTITYMGT_REFINED", "WFM_REFINED", "TM_REFINED"
    );

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

    @GetMapping("/admin/discover-tables")
    public Map<String, Object> discoverTables() {
        if (!snowflakeExecutor.isConfigured()) {
            return Map.of("error", "Snowflake not configured");
        }

        Map<String, List<String>> allTables = new LinkedHashMap<>();
        for (String schema : DISCOVERY_SCHEMAS) {
            try {
                String sql = "SELECT TABLE_NAME FROM DATAHUB.INFORMATION_SCHEMA.TABLES " +
                        "WHERE TABLE_SCHEMA = '" + schema + "' ORDER BY TABLE_NAME";
                List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
                List<String> tables = rows.stream()
                        .map(r -> String.valueOf(r.get("TABLE_NAME")))
                        .collect(Collectors.toList());
                allTables.put(schema, tables);
                log.info("Discovered {} tables in {}", tables.size(), schema);
            } catch (Exception e) {
                log.warn("Failed to list tables in {}: {}", schema, e.getMessage());
                allTables.put(schema, List.of("ERROR: " + e.getMessage()));
            }
        }
        return Map.of("schemas", allTables);
    }
}
