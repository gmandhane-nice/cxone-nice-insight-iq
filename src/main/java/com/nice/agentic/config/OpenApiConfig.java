package com.nice.agentic.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Agentic Contact Center Analytics API",
                version = "1.0.0",
                description = "AI-powered predictive analytics platform for contact center supervisors. "
                        + "Provides real-time anomaly detection, demand forecasting, burnout risk scoring, "
                        + "staffing simulation, and ROI quantification powered by live Snowflake data.",
                contact = @Contact(name = "NICE CXone team")
        )
)
public class OpenApiConfig {
}
