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
 * Orchestrator Agent - analyzes the user question and creates an investigation plan.
 * <p>
 * Makes ONE Bedrock call (no tools) to decompose the question into sub-tasks for specialized agents.
 */
@Component
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);

    private final BedrockHelper bedrockHelper;
    private final BedrockConfig.BedrockSettings settings;
    private final String systemPrompt;

    public OrchestratorAgent(BedrockHelper bedrockHelper,
                             BedrockConfig.BedrockSettings settings) throws IOException {
        this.bedrockHelper = bedrockHelper;
        this.settings = settings;
        this.systemPrompt = new String(
                new ClassPathResource("prompts/orchestrator-system.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /**
     * Plans the investigation by decomposing the question into sub-tasks.
     *
     * @param question the user's natural-language question
     * @return JSON string with investigation plan: {"tasks": [{"agent": "...", "briefing": "..."}]}
     */
    public String plan(String question) {
        log.info("Orchestrator planning investigation for question: {}", question);

        String result = bedrockHelper.singleTurn(
                systemPrompt,
                question,
                settings.getModelId(),
                settings.getMaxTokens(),
                (float) settings.getTemperature()
        );

        log.info("Orchestrator produced plan: {} chars", result.length());
        return result;
    }
}
