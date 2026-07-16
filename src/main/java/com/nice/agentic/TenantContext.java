package com.nice.agentic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TenantContext {

    private volatile String tenantId;
    private final String defaultTenantId;

    public TenantContext(@Value("${agentic.tenant-id:11eb0fd4-c54b-6c70-a061-0242ac110003}") String tenantId) {
        this.tenantId = tenantId;
        this.defaultTenantId = tenantId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDefaultTenantId() {
        return defaultTenantId;
    }
}
