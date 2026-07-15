package com.nice.agentic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the active tenant ID for the current deployment.
 *
 * In production this would come from the supervisor's JWT/session.
 * For Sparkathon demo, it's configured via {@code agentic.tenant-id} property.
 *
 * Dev tenant: {@code 11efd95f-eed7-42e0-a6c9-0242ac110002} (16K+ contacts in Snowflake dev).
 */
@Component
public class TenantContext {

    private final String tenantId;

    public TenantContext(@Value("${agentic.tenant-id:11efd95f-eed7-42e0-a6c9-0242ac110002}") String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTenantId() {
        return tenantId;
    }
}
