package com.nice.agentic.agents;

import com.nice.agentic.BedrockConfig;
import com.nice.agentic.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Historical Agent - specializes in historical trends from Snowflake and OpenSearch.
 * <p>
 * Has access to: metric_history, contact_volume_breakdown, agent_leaderboard, ad_hoc_query.
 */
@Component
public class HistoricalAgent implements SubAgent {

    private static final Logger log = LoggerFactory.getLogger(HistoricalAgent.class);
    private static final int MAX_ITERATIONS = 5;

    private final BedrockRuntimeClient bedrock;
    private final String modelId;
    private final int maxTokens;
    private final float temperature;
    private final Map<String, AgentTool> toolsByName;
    private final ToolConfiguration toolConfiguration;
    private final String systemPrompt;

    public HistoricalAgent(BedrockRuntimeClient bedrock,
                           BedrockConfig.BedrockSettings settings,
                           @Value("${agentic.bedrock.haiku-model-id:}") String haikuModelId,
                           MetricHistoryTool metricHistoryTool,
                           ContactVolumeTool contactVolumeTool,
                           AgentPerformanceTool agentPerformanceTool,
                           AdHocQueryTool adHocQueryTool) throws IOException {
        this.bedrock = bedrock;
        this.modelId = (haikuModelId != null && !haikuModelId.isEmpty())
                ? haikuModelId
                : settings.getModelId();
        this.maxTokens = settings.getMaxTokens();
        this.temperature = (float) settings.getTemperature();

        // Register historical tools
        this.toolsByName = new HashMap<>();
        List<Tool> bedrockTools = new ArrayList<>();

        List<AgentTool> tools = List.of(metricHistoryTool, contactVolumeTool, agentPerformanceTool, adHocQueryTool);
        for (AgentTool t : tools) {
            toolsByName.put(t.name(), t);
            bedrockTools.add(Tool.builder()
                    .toolSpec(ToolSpecification.builder()
                            .name(t.name())
                            .description(t.description())
                            .inputSchema(ToolInputSchema.builder().json(t.inputSchema()).build())
                            .build())
                    .build());
        }

        this.toolConfiguration = ToolConfiguration.builder().tools(bedrockTools).build();
        this.systemPrompt = new String(
                new ClassPathResource("prompts/historical-system.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Override
    public String name() {
        return "historical";
    }

    @Override
    public String investigate(String briefing, Map<String, Object> context) {
        log.info("HistoricalAgent investigating: {}", briefing);

        List<Message> conversation = new ArrayList<>();
        conversation.add(Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(briefing))
                .build());

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            ConverseRequest request = ConverseRequest.builder()
                    .modelId(modelId)
                    .system(SystemContentBlock.fromText(systemPrompt))
                    .messages(conversation)
                    .toolConfig(toolConfiguration)
                    .inferenceConfig(InferenceConfiguration.builder()
                            .maxTokens(maxTokens)
                            .temperature(temperature)
                            .build())
                    .build();

            ConverseResponse response = bedrock.converse(request);
            Message assistant = response.output().message();
            conversation.add(assistant);

            List<ToolUseBlock> toolUses = new ArrayList<>();
            StringBuilder textOut = new StringBuilder();
            for (ContentBlock block : assistant.content()) {
                if (block.toolUse() != null) toolUses.add(block.toolUse());
                if (block.text() != null) textOut.append(block.text());
            }

            if (response.stopReason() == StopReason.TOOL_USE && !toolUses.isEmpty()) {
                List<ContentBlock> toolResultBlocks = new ArrayList<>();
                for (ToolUseBlock use : toolUses) {
                    AgentTool tool = toolsByName.get(use.name());
                    String result = tool == null
                            ? "{\"error\":\"unknown tool: " + use.name() + "\"}"
                            : tool.invoke(use.input());
                    log.info("HistoricalAgent tool {} -> {} chars", use.name(), result.length());
                    toolResultBlocks.add(ContentBlock.fromToolResult(
                            ToolResultBlock.builder()
                                    .toolUseId(use.toolUseId())
                                    .content(ToolResultContentBlock.fromText(result))
                                    .build()));
                }
                conversation.add(Message.builder()
                        .role(ConversationRole.USER)
                        .content(toolResultBlocks)
                        .build());
                continue;
            }

            String finalText = textOut.toString().trim();
            log.info("HistoricalAgent completed investigation: {} chars", finalText.length());
            return finalText;
        }

        log.warn("HistoricalAgent hit iteration limit");
        return "{\"findings\":[{\"metric\":\"unknown\",\"trend\":\"unknown\",\"observation\":\"Investigation did not converge\",\"evidence\":[]}]}";
    }
}
