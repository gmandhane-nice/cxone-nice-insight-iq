package com.nice.agentic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
public class BedrockConfig {

    private static final Logger log = LoggerFactory.getLogger(BedrockConfig.class);

    @Value("${agentic.bedrock.region}")
    private String region;

    @Value("${agentic.bedrock.api-key:}")
    private String apiKey;

    @Value("${agentic.bedrock.fallback-region:}")
    private String fallbackRegion;

    @Bean
    @org.springframework.context.annotation.Primary
    public BedrockRuntimeClient bedrockRuntimeClient() {
        if (apiKey != null && !apiKey.isEmpty()) {
            log.info("BedrockConfig: Using API key auth (region={})", region);
            return BedrockRuntimeClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(AnonymousCredentialsProvider.create())
                    .overrideConfiguration(c -> c.addExecutionInterceptor(new BedrockApiKeyInterceptor(apiKey)))
                    .build();
        }
        log.info("BedrockConfig: Using default credentials (region={})", region);
        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .build();
    }

    @Bean("fallbackBedrockClient")
    public BedrockRuntimeClient fallbackBedrockClient() {
        String fbRegion = (fallbackRegion != null && !fallbackRegion.isEmpty()) ? fallbackRegion : "us-west-2";
        log.info("BedrockConfig: Fallback client (region={})", fbRegion);
        return BedrockRuntimeClient.builder()
                .region(Region.of(fbRegion))
                .build();
    }

    @Bean
    @ConfigurationProperties(prefix = "agentic.bedrock")
    public BedrockSettings bedrockSettings() {
        return new BedrockSettings();
    }

    private static class BedrockApiKeyInterceptor implements ExecutionInterceptor {
        private final String token;

        BedrockApiKeyInterceptor(String token) {
            this.token = token;
        }

        @Override
        public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
            return context.httpRequest().toBuilder()
                    .putHeader("Authorization", "Bearer " + token)
                    .removeHeader("X-Amz-Security-Token")
                    .build();
        }
    }

    public static class BedrockSettings {
        private String region;
        private String modelId;
        private int maxTokens = 2048;
        private double temperature = 0.2;
        private int maxAgentIterations = 6;

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxAgentIterations() { return maxAgentIterations; }
        public void setMaxAgentIterations(int maxAgentIterations) { this.maxAgentIterations = maxAgentIterations; }
    }
}
