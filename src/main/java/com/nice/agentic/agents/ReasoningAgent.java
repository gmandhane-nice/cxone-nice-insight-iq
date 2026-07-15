package com.nice.agentic.agents;

import com.nice.agentic.BedrockConfig;
import com.nice.agentic.BedrockHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Reasoning Agent - correlates evidence from all sub-agents and generates ranked hypotheses.
 * <p>
 * Makes ONE Bedrock call (no tools) to analyze evidence and produce root-cause hypotheses.
 */
@Component
public class ReasoningAgent {

    private static final Logger log = LoggerFactory.getLogger(ReasoningAgent.class);

    private final BedrockHelper bedrockHelper;
    private final BedrockConfig.BedrockSettings settings;
    private final String systemPrompt;

    public ReasoningAgent(BedrockHelper bedrockHelper,
                          BedrockConfig.BedrockSettings settings) throws IOException {
        this.bedrockHelper = bedrockHelper;
        this.settings = settings;
        this.systemPrompt = new String(
                new ClassPathResource("prompts/reasoning-system.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /**
     * Correlates evidence from sub-agents and generates ranked root-cause hypotheses.
     *
     * @param evidence map of agent name -> investigation results (JSON strings)
     * @return JSON string with hypotheses: {"hypotheses": [{"title": "...", "confidence": ..., ...}]}
     */
    public String correlate(Map<String, String> evidence) {
        log.info("ReasoningAgent correlating evidence from {} agents", evidence.size());

        // Build prompt with evidence from each sub-agent
        StringBuilder prompt = new StringBuilder();
        prompt.append("Evidence from specialized investigation agents:\n\n");

        evidence.forEach((agentName, findings) -> {
            prompt.append("=== ").append(agentName.toUpperCase()).append(" AGENT ===\n");
            prompt.append(findings).append("\n\n");
        });

        prompt.append("Based on this evidence, identify the most probable root causes.");

        String result = bedrockHelper.singleTurn(
                systemPrompt,
                prompt.toString(),
                settings.getModelId(),
                settings.getMaxTokens(),
                (float) settings.getTemperature()
        );

        log.info("ReasoningAgent produced hypotheses: {} chars", result.length());
        return result;
    }
}
