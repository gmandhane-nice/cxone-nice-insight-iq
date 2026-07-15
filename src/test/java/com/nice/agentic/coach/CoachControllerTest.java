package com.nice.agentic.coach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.BedrockConfig;
import com.nice.agentic.BedrockHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for POST /coach/trigger.
 *
 * BedrockHelper is mocked so no real AWS calls are made.
 * BedrockConfig.BedrockSettings is also mocked to satisfy the CoachController constructor.
 */
@WebMvcTest(CoachController.class)
class CoachControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BedrockHelper bedrockHelper;

    @MockBean
    private BedrockConfig.BedrockSettings bedrockSettings;

    @Test
    void triggerReturns200WithNonEmptyNudge() throws Exception {
        // Arrange: mock Bedrock to return a canned nudge (first call = nudge, second = tone critic)
        when(bedrockHelper.singleTurn(anyString(), anyString(), anyString(), anyInt(), anyFloat()))
                .thenReturn("Try using the de-escalation technique from module B4 — pausing briefly before responding can reduce AHT significantly.")
                .thenReturn("{\"approved\": true, \"reason\": \"Supportive and actionable.\"}");

        CoachController.TriggerRequest request = new CoachController.TriggerRequest(
                "AG-042",
                "Jordan M.",
                "AHT",
                612.0,
                498.0,
                22.9,
                "Banking",
                List.of("billing-dispute", "billing-dispute", "account-inquiry"),
                "B4",
                "De-escalation Techniques for Billing Disputes"
        );

        // Act & Assert
        mockMvc.perform(post("/coach/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value("AG-042"))
                .andExpect(jsonPath("$.nudge").value(notNullValue()))
                .andExpect(jsonPath("$.nudge").value(not(emptyString())))
                .andExpect(jsonPath("$.metric").value("AHT"))
                .andExpect(jsonPath("$.deltaPct").value(22.9))
                .andExpect(jsonPath("$.generatedAt").value(notNullValue()));
    }
}
