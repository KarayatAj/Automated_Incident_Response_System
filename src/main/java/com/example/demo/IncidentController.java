package com.example.demo;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.google.genai.GoogleGenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api")
public class IncidentController {

    private final IncidentAgents.DetectiveAgent detectiveAgent;
    private final IncidentAgents.ResponderAgent responderAgent;
    private final JsonMapper jsonMapper;

    public IncidentController(GraphTools graphTools) {
        ChatModel geminiModel = GoogleGenAiChatModel.builder()
                .projectId("agentic-response-demo")
                .location("asia-south1")
                .modelName("gemini-3.5-flash-lite")
                .temperature(0.2)
                .build();

        this.detectiveAgent = AiServices.builder(IncidentAgents.DetectiveAgent.class)
                .chatModel(geminiModel)
                .tools(graphTools)
                .build();

        this.responderAgent = AiServices.builder(IncidentAgents.ResponderAgent.class)
                .chatModel(geminiModel)
                .tools(graphTools)
                .build();

        this.jsonMapper = JsonMapper.builder().build();
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handleAlert(@RequestBody String payload) {
        System.out.println("Alert received! Raw payload: " + payload);

        String service = "unknown-service";
        String incident = "unspecified-incident";
        String severity = "UNKNOWN";

        try {
            JsonNode root = jsonMapper.readTree(payload);
            service = root.path("service").asString("unknown-service");
            incident = root.path("incident").asString("unspecified-incident");
            severity = root.path("severity").asString("UNKNOWN");
        } catch (Exception e) {
            System.err.println("Payload is not structured JSON, using defaults: " + e.getMessage());
        }

        try {
            String detectiveFindings = detectiveAgent.investigate(service, incident, severity, payload);
            System.out.println("=== Detective Agent Findings ===\n" + detectiveFindings);

            String responderSummary = responderAgent.respondAndRecord(service, incident, severity, detectiveFindings);
            System.out.println("=== Responder Agent Summary ===\n" + responderSummary);

            return ResponseEntity.ok(responderSummary);
        } catch (Exception e) {
            System.err.println("Error processing incident: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}
