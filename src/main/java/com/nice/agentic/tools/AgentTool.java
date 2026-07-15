package com.nice.agentic.tools;

import software.amazon.awssdk.core.document.Document;

public interface AgentTool {
    String name();
    String description();
    Document inputSchema();
    String invoke(Document arguments);
}
