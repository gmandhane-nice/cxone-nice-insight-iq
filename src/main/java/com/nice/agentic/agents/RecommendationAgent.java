package com.nice.agentic.agents;

import com.nice.agentic.BedrockConfig;
import com.nice.agentic.BedrockHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Recommendation Agent - produces actionable recommendations from ranked hypotheses.
 * <p>
 * Makes ONE Bedrock call (no tools) to generate concrete actions, impact forecasts, and urgency levels.
 */
@Component
public class RecommendationAgent {

    private static final Logger log = LoggerFactory.getLogger(RecommendationAgent.class);

    private final BedrockHelper bedrockHelper;
    private final BedrockConfig.BedrockSettings settings;
    private final String systemPrompt;

    public RecommendationAgent(BedrockHelper bedrockHelper,
                               BedrockConfig.BedrockSettings settings) throws IOException {
        this.bedrockHelper = bedrockHelper;
        this.settings = settings;
        this.systemPrompt = new String(
                new ClassPathResource("prompts/recommendation-system.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /**
     * Produces actionable recommendations from ranked root-cause hypotheses.
     *
     * @param hypotheses JSON string from ReasoningAgent with ranked hypotheses
     * @return JSON string with recommendations: {"summary": "...", "recommendations": [...]}
     */
    public String recommend(String hypotheses) {
        log.info("RecommendationAgent producing recommendations from hypotheses");

        String prompt = "Ranked root-cause hypotheses:\n\n" + hypotheses +
                "\n\nBased on these hypotheses, produce actionable recommendations.";

        String result = bedrockHelper.singleTurn(
                systemPrompt,
                prompt,
                settings.getModelId(),
                settings.getMaxTokens(),
                (float) settings.getTemperature()
        );

        log.info("RecommendationAgent produced recommendations: {} chars", result.length());
        return result;
    }
}
