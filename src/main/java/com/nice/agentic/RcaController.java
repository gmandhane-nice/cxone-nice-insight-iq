package com.nice.agentic;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rca/v1")
public class RcaController {

    private final RcaAgent agent;

    public RcaController(RcaAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/ask")
    public RcaAgent.AgentResult ask(@RequestBody AskRequest request) {
        return agent.ask(request.question());
    }

    public record AskRequest(String question) {}
}
