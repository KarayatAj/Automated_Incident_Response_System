package com.example.demo;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@RestController
@RequestMapping("/api")
public class IncidentController implements DisposableBean {

    private final Driver neo4jDriver;
    private final ChatModel geminiModel;
    private final JsonMapper jsonMapper;

    public IncidentController(
            @Value("${spring.neo4j.uri}") String uri,
            @Value("${spring.neo4j.authentication.username}") String user,
            @Value("${spring.neo4j.authentication.password}") String password) {

        this.neo4jDriver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        this.geminiModel = VertexAiGeminiChatModel.builder()
                .project("agentic-response-demo")
                .location("asia-south1")
                .modelName("gemini-3.5-flash-lite")
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

        try (Session session = neo4jDriver.session()) {
            // 1. Fetch direct upstream/downstream blast radius for the affected service
            List<String> dependencyLines = session.run(
                    """
                    MATCH (s {name: $service})
                    WHERE NOT s:Incident
                    OPTIONAL MATCH (s)-[r]-(neighbor)
                    WHERE NOT neighbor:Incident
                    RETURN labels(s) AS srcLabels,
                           properties(s) AS srcProps,
                           type(r) AS relType,
                           CASE WHEN r IS NULL THEN null ELSE startNode(r) = s END AS isOutgoing,
                           labels(neighbor) AS neighborLabels,
                           properties(neighbor) AS neighborProps
                    """,
                    Values.parameters("service", service)
            ).list(r -> {
                String src = r.get("srcLabels").asList() + " " + r.get("srcProps").asMap();
                if (r.get("relType").isNull()) {
                    return src + " (no connected dependencies)";
                }
                String arrow = r.get("isOutgoing").asBoolean()
                        ? " -[" + r.get("relType").asString() + "]-> "
                        : " <-[" + r.get("relType").asString() + "]- ";
                String neighbor = r.get("neighborLabels").asList() + " " + r.get("neighborProps").asMap();
                return src + arrow + neighbor;
            });

            // Fallback to general topology if the service isn't in the graph yet
            String graphContext;
            if (!dependencyLines.isEmpty()) {
                graphContext = String.join("\n", dependencyLines);
            } else {
                List<String> fallbackNodes = session.run(
                        "MATCH (n) WHERE NOT n:Incident RETURN labels(n) AS labels, properties(n) AS props LIMIT 10"
                ).list(r -> r.get("labels").asList() + " " + r.get("props").asMap());
                graphContext = fallbackNodes.isEmpty()
                        ? "No existing services in graph yet."
                        : "Target service '" + service + "' not yet mapped. Existing graph:\n" + String.join("\n", fallbackNodes);
            }

            // 2. Ask Gemini to analyze the incident and blast radius
            String prompt = """
                    You are an automated SRE Detective Agent.
                    Analyze the following incident alert using the Neo4j service topology below.
                    Provide a concise 2-3 sentence root-cause hypothesis, identify any impacted upstream or downstream components, and state the recommended next step.

                    Service Topology Context:
                    %s

                    Incident Alert:
                    %s
                    """.formatted(graphContext, payload);

            String analysis = geminiModel.chat(prompt);
            System.out.println("Detective Agent Analysis:\n" + analysis);

            // 3. Create (:Incident) and link it to the existing node (or create a :Service node if absent)
            List<Record> linked = session.run(
                    """
                    MATCH (target {name: $service})
                    WHERE NOT target:Incident
                    WITH target LIMIT 1
                    CREATE (i:Incident {
                        service: $service,
                        incident: $incident,
                        severity: $severity,
                        payload: $payload,
                        analysis: $analysis,
                        createdAt: datetime()
                    })
                    MERGE (i)-[:AFFECTS]->(target)
                    RETURN elementId(i) AS id, labels(target) AS targetLabels, target.name AS targetName
                    """,
                    Values.parameters(
                            "service", service,
                            "incident", incident,
                            "severity", severity,
                            "payload", payload,
                            "analysis", analysis
                    )
            ).list();

            Record saved;
            if (!linked.isEmpty()) {
                saved = linked.get(0);
            } else {
                saved = session.run(
                        """
                        MERGE (target:Service {name: $service})
                        CREATE (i:Incident {
                            service: $service,
                            incident: $incident,
                            severity: $severity,
                            payload: $payload,
                            analysis: $analysis,
                            createdAt: datetime()
                        })
                        MERGE (i)-[:AFFECTS]->(target)
                        RETURN elementId(i) AS id, labels(target) AS targetLabels, target.name AS targetName
                        """,
                        Values.parameters(
                                "service", service,
                                "incident", incident,
                                "severity", severity,
                                "payload", payload,
                                "analysis", analysis
                        )
                ).single();
            }

            String nodeId = saved.get("id").asString();
            String targetInfo = saved.get("targetLabels").asList() + " (" + saved.get("targetName").asString() + ")";
            System.out.println("Saved Incident " + nodeId + " -[:AFFECTS]-> " + targetInfo);

            return ResponseEntity.ok("Incident processed and linked to " + targetInfo + ". Node ID: " + nodeId);
        } catch (Exception e) {
            System.err.println("Error processing incident: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @Override
    public void destroy() {
        neo4jDriver.close();
    }
}
