package com.nice.agentic.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the verified table→columns mapping from a stored JSON file (schema-columns.json).
 * No Snowflake queries at startup — the JSON is pre-generated and checked in.
 * To refresh: run the /admin/refresh-schema endpoint (or manually update the JSON).
 */
@Component
public class SchemaDiscovery {

    private static final Logger log = LoggerFactory.getLogger(SchemaDiscovery.class);
    private static final String SCHEMA_FILE = "schema-columns.json";

    private final Map<String, List<String>> tableColumns = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void loadSchema() {
        try {
            InputStream is = new ClassPathResource(SCHEMA_FILE).getInputStream();
            Map<String, List<String>> loaded = mapper.readValue(is,
                    new TypeReference<LinkedHashMap<String, List<String>>>() {});
            tableColumns.putAll(loaded);
            log.info("SchemaDiscovery: Loaded {} tables from {}", tableColumns.size(), SCHEMA_FILE);
        } catch (IOException e) {
            log.warn("SchemaDiscovery: {} not found, using fallback", SCHEMA_FILE);
            loadFallbackSchema();
        }
    }

    public Map<String, List<String>> getTableColumns() {
        return tableColumns;
    }

    public String getSchemaReference() {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (Map.Entry<String, List<String>> entry : tableColumns.entrySet()) {
            String table = entry.getKey();
            sb.append(idx++).append(". ").append(table).append("\n");
            sb.append("   Columns: ").append(String.join(", ", entry.getValue())).append("\n\n");
        }
        return sb.toString();
    }

    private void loadFallbackSchema() {
        tableColumns.put("DATAHUB.ENTITYMGT_REFINED.AGENT_SESSION_ACTIVITY_FACT", List.of(
                "AGENT_SESSION_ACTIVITY_KEY", "AGENT_SESSION_KEY", "AGENT_SESSION_ID", "AGENT_SESSION_NO",
                "AGENT_SESSION_ACTIVITY_SEQUENCE", "AGENT_KEY", "AGENT_STATE_KEY",
                "START_TIMESTAMP", "END_TIMESTAMP", "AGENT_SESSION_START_TIMESTAMP",
                "AGENT_SESSION_ACTIVITY_DURATION_SECONDS", "STATION_NO", "STATION_PHONE_NO",
                "AGENT_NO", "USER_ID", "AGENT_STATE_NO", "UNAVAILABLE_STATE_NAME",
                "_TENANT_ID", "_CREATED_TIMESTAMP"));

        tableColumns.put("DATAHUB.SUITE_REFINED.AGENT_CONTACT_FACT_VIEW_V011", List.of(
                "AGENT_CONTACT_KEY", "AGENT_CONTACT_ID", "AGENT_NO", "USER_ID",
                "START_TIMESTAMP", "END_TIMESTAMP", "AGENT_SESSION_ID", "AGENT_SESSION_NO",
                "CONTACT_NO", "CHANNEL_NO", "SKILL_NO", "DIRECTION_NO",
                "HANDLE_SECONDS", "TALK_TIME_SECONDS", "ACW_SECONDS", "HOLD_SECONDS",
                "ACTIVE_SECONDS", "IS_REFUSED_FLAG", "REFUSED_COUNT",
                "IS_TRANSFERRED_FLAG", "AGENT_CONTACT_DURATION_SECONDS",
                "_TENANT_ID", "_CREATED_TIMESTAMP"));

        tableColumns.put("DATAHUB.SUITE_REFINED.USER_DIM_VIEW_V001", List.of(
                "USER_ID", "USER_FIRST_NAME", "USER_LAST_NAME", "_TENANT_ID"));

        tableColumns.put("DATAHUB.SUITE_REFINED.AGENT_STATE_DIM_VIEW_V011", List.of(
                "AGENT_STATE_KEY", "AGENT_STATE_NAME"));

        tableColumns.put("DATAHUB.SUITE_REFINED.SKILL_SCD_DIM_VIEW_V001", List.of(
                "SKILL_KEY", "SKILL_NO", "SKILL_NAME", "_TENANT_ID"));

        tableColumns.put("DATAHUB.SUITE_REFINED.CHANNEL_DIM_VIEW_V001", List.of(
                "CHANNEL_KEY", "CHANNEL_NO", "CHANNEL_NAME"));

        tableColumns.put("DATAHUB.SUITE_REFINED.CONTACT_STATE_DIM_VIEW_V001", List.of(
                "CONTACT_STATE_KEY", "CONTACT_STATE_NO", "CONTACT_STATE_NAME"));

        tableColumns.put("DATAHUB.TM_REFINED.TENANT_DIM_VIEW_V001", List.of(
                "_TENANT_ID", "BILLING_ID", "TENANT_NAME", "TENANT_SCHEMA_NAME"));
    }
}
