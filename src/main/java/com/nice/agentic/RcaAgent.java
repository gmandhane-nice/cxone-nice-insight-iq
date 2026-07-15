package com.nice.agentic;

import com.nice.agentic.tools.AgentTool;
import com.nice.agentic.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @deprecated Legacy single-agent RCA implementation. Use {@link com.nice.agentic.agents.MultiAgentOrchestrator} instead.
 */
@Deprecated
@Service
public class RcaAgent {

    private static final Logger log = LoggerFactory.getLogger(RcaAgent.class);

    private final BedrockRuntimeClient bedrock;
    private final BedrockConfig.BedrockSettings settings;
    private final Map<String, AgentTool> toolsByName;
    private final ToolConfiguration toolConfiguration;
    private final String systemPrompt;

    public RcaAgent(BedrockRuntimeClient bedrock,
                    BedrockConfig.BedrockSettings settings,
                    ToolRegistry registry) throws IOException {
        this.bedrock = bedrock;
        this.settings = settings;
        this.toolsByName = new HashMap<>();
        List<Tool> bedrockTools = new ArrayList<>();
        for (AgentTool t : registry.getActiveTools()) {
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
                new ClassPathResource("prompts/rca-system.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    public AgentResult ask(String question) {
        return askStream(question, traceEntry -> {});
    }

    /**
     * Runs the agent loop, calling {@code onEvent} after each tool call/result and
     * once more with the final answer trace entry.
     *
     * @param question the natural-language question to investigate
     * @param onEvent  callback invoked for each {@link TraceEntry} (tool_call or final)
     * @return the completed {@link AgentResult}
     */
    public AgentResult askStream(String question, Consumer<TraceEntry> onEvent) {
        List<Message> conversation = new ArrayList<>();
        List<TraceEntry> trace = new ArrayList<>();

        conversation.add(Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(question))
                .build());

        for (int iteration = 0; iteration < settings.getMaxAgentIterations(); iteration++) {
            ConverseRequest request = ConverseRequest.builder()
                    .modelId(settings.getModelId())
                    .system(SystemContentBlock.fromText(systemPrompt))
                    .messages(conversation)
                    .toolConfig(toolConfiguration)
                    .inferenceConfig(InferenceConfiguration.builder()
                            .maxTokens(settings.getMaxTokens())
                            .temperature((float) settings.getTemperature())
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
                    log.info("tool {} -> {} chars", use.name(), result.length());
                    TraceEntry entry = new TraceEntry("tool_call", use.name(), use.input().toString(), result);
                    trace.add(entry);
                    onEvent.accept(entry);
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
            TraceEntry finalEntry = new TraceEntry("final", "assistant", null, finalText);
            trace.add(finalEntry);
            onEvent.accept(finalEntry);
            return new AgentResult(finalText, trace);
        }

        TraceEntry timeoutEntry = new TraceEntry("final", "assistant", null,
                "{\"summary\":\"Investigation did not converge within the iteration budget.\",\"causes\":[]}");
        trace.add(timeoutEntry);
        onEvent.accept(timeoutEntry);
        return new AgentResult(timeoutEntry.output(), trace);
    }

    public record TraceEntry(String type, String name, String input, String output) {}

    public record AgentResult(String answerJson, List<TraceEntry> trace) {}
}
