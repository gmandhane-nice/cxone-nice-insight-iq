package com.nice.agentic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * Lightweight helper for single-turn Bedrock converse calls (no tools).
 * Used by CoachController to avoid duplicating Bedrock SDK boilerplate.
 */
@Service
public class BedrockHelper {

    private static final Logger log = LoggerFactory.getLogger(BedrockHelper.class);

    private final BedrockRuntimeClient bedrock;

    public BedrockHelper(BedrockRuntimeClient bedrock) {
        this.bedrock = bedrock;
    }

    /**
     * Perform a single-turn converse call and return the assistant's text response.
     *
     * @param systemPrompt system prompt text
     * @param userMessage  user message text
     * @param modelId      Bedrock model ID
     * @param maxTokens    maximum tokens for the response
     * @param temperature  sampling temperature
     * @return assistant text response
     * @throws RuntimeException if the Bedrock call fails or returns no text content
     */
    public String singleTurn(String systemPrompt, String userMessage, String modelId,
                             int maxTokens, float temperature) {
        try {
            ConverseRequest request = ConverseRequest.builder()
                    .modelId(modelId)
                    .system(SystemContentBlock.fromText(systemPrompt))
                    .messages(Message.builder()
                            .role(ConversationRole.USER)
                            .content(ContentBlock.fromText(userMessage))
                            .build())
                    .inferenceConfig(InferenceConfiguration.builder()
                            .maxTokens(maxTokens)
                            .temperature(temperature)
                            .build())
                    .build();

            ConverseResponse response = bedrock.converse(request);

            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : response.output().message().content()) {
                if (block.text() != null) {
                    sb.append(block.text());
                }
            }

            String text = sb.toString().trim();
            if (text.isEmpty()) {
                throw new RuntimeException("Bedrock returned empty text content for model: " + modelId);
            }
            return text;

        } catch (RuntimeException e) {
            log.error("BedrockHelper.singleTurn failed for model={}: {}", modelId, e.getMessage());
            throw new RuntimeException("Bedrock call failed for model " + modelId + ": " + e.getMessage(), e);
        }
    }
}
