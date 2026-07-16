package com.nice.agentic;

import com.nice.agentic.query.SnowflakeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/tenant")
public class TenantController {

    private static final Logger log = LoggerFactory.getLogger(TenantController.class);

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;

    public TenantController(SnowflakeExecutor snowflakeExecutor, TenantContext tenantContext) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/list")
    public Map<String, Object> listTopTenants() {
        if (!snowflakeExecutor.isConfigured()) {
            return Map.of(
                    "current", tenantContext.getTenantId(),
                    "tenants", List.of(Map.of(
                            "tenantId", tenantContext.getTenantId(),
                            "tenantName", "Default Tenant",
                            "contactCount", 0
                    ))
            );
        }

        try {
            String sql = "SELECT ac._TENANT_ID, " +
                    "COALESCE(t.TENANT_NAME, ac._TENANT_ID) AS TENANT_NAME, " +
                    "COUNT(*) AS CONTACT_COUNT " +
                    "FROM DATAHUB.SUITE_REFINED.AGENT_CONTACT_FACT_VIEW_V011 ac " +
                    "LEFT JOIN DATAHUB.TM_REFINED.TENANT_DIM_VIEW_V001 t " +
                    "  ON ac._TENANT_ID = t._TENANT_ID " +
                    "WHERE ac.START_TIMESTAMP >= DATEADD(day, -15, CURRENT_TIMESTAMP()) " +
                    "GROUP BY ac._TENANT_ID, t.TENANT_NAME " +
                    "ORDER BY CONTACT_COUNT DESC " +
                    "LIMIT 10";

            List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
            List<Map<String, Object>> tenants = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> tenant = new LinkedHashMap<>();
                tenant.put("tenantId", row.get("_TENANT_ID"));
                tenant.put("tenantName", row.get("TENANT_NAME"));
                tenant.put("contactCount", row.get("CONTACT_COUNT"));
                tenants.add(tenant);
            }

            log.info("Discovered {} active tenants", tenants.size());
            return Map.of(
                    "current", tenantContext.getTenantId(),
                    "tenants", tenants
            );
        } catch (Exception e) {
            log.error("Failed to list tenants: {}", e.getMessage());
            return Map.of(
                    "current", tenantContext.getTenantId(),
                    "tenants", List.of(Map.of(
                            "tenantId", tenantContext.getTenantId(),
                            "tenantName", "Default Tenant",
                            "contactCount", 0
                    )),
                    "error", e.getMessage()
            );
        }
    }

    @PostMapping("/switch")
    public Map<String, Object> switchTenant(@RequestBody Map<String, String> body) {
        String newTenantId = body.get("tenantId");
        if (newTenantId == null || newTenantId.isBlank()) {
            return Map.of("error", "tenantId is required");
        }
        String previousTenantId = tenantContext.getTenantId();
        tenantContext.setTenantId(newTenantId);
        log.info("Switched tenant: {} → {}", previousTenantId, newTenantId);
        return Map.of(
                "status", "ok",
                "previous", previousTenantId,
                "current", newTenantId
        );
    }

    @GetMapping("/current")
    public Map<String, String> current() {
        return Map.of("tenantId", tenantContext.getTenantId());
    }
}
