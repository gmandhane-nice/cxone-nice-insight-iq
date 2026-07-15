# agentic-rca-sample

Minimal, working prototype of the **Conversational Root-Cause Analysis** capability from the design.

Ask a natural-language question about a contact-center metric ("Why has Banking AHT increased today?"). The service uses **Claude on AWS Bedrock** with a tool-use agent loop to call three mock data tools (standing in for Valkey + OpenSearch) and returns a ranked, evidence-backed answer.

## What's in here

| Piece | Purpose |
|---|---|
| `RcaAgent` | Bedrock `Converse` client + agent loop (tool_use → tool_result → repeat until final) |
| `AgentTool` interface | Contract every tool implements — name, description, JSON schema, invoke |
| `MetricSnapshotTool` / `MetricHistoryTool` / `AgentPerformanceTool` | Three tools the LLM can pick from |
| `MockDataStore` | Hard-coded data that tells a coherent "Banking AHT" story |
| `prompts/rca-system.txt` | System prompt (git-tracked, versioned) |
| `RcaController` + `index.html` | REST + minimal chat UI |

## Prerequisites

- Java 17
- Maven 3.9+
- AWS credentials with `bedrock:InvokeModel` on Claude in `us-west-2` (already confirmed for the current federated role)

## Run

```bash
cd agentic-rca-sample
mvn spring-boot:run
```

Then open http://localhost:8080

Or hit the API directly:

```bash
curl -s http://localhost:8080/rca/v1/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"Why has the Banking queue AHT increased today?"}' | jq
```

## Configuration

`src/main/resources/application.yaml` — change `model-id` to any Bedrock Claude inference profile you have access to.
