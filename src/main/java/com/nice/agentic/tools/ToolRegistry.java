package com.nice.agentic.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Filters the full list of registered {@link AgentTool} beans to those whose
 * {@link ToolScope} annotation includes the active scope ({@code agentic.tool-scope}).
 *
 * Tools without a {@code @ToolScope} annotation are excluded.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final List<AgentTool> activeTools;

    public ToolRegistry(List<AgentTool> allTools,
                        @Value("${agentic.tool-scope:rca}") String activeScope) {

        this.activeTools = allTools.stream()
                .filter(tool -> {
                    ToolScope ann = tool.getClass().getAnnotation(ToolScope.class);
                    return ann != null && Arrays.asList(ann.value()).contains(activeScope);
                })
                .toList();

        log.info("Registered {} tools for scope '{}'", activeTools.size(), activeScope);
    }

    /**
     * Returns the tools that are active for the configured scope.
     */
    public List<AgentTool> getActiveTools() {
        return activeTools;
    }
}
