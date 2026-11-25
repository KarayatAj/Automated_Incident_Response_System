package com.example.demo;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GraphTools implements DisposableBean {

    private final Driver neo4jDriver;

    public GraphTools(
            @Value("${spring.neo4j.uri}") String uri,
            @Value("${spring.neo4j.authentication.username}") String user,
            @Value("${spring.neo4j.authentication.password}") String password) {
        this.neo4jDriver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    @Tool("Get upstream and downstream infrastructure dependencies (blast radius) for a given service or database name")
    public String getServiceBlastRadius(@P("Exact name of the service or component, e.g., UserDB or FrontendApp") String serviceName) {
        System.out.println("[TOOL] getServiceBlastRadius called for: " + serviceName);
        try (Session session = neo4jDriver.session()) {
            List<String> lines = session.run(
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
                    Values.parameters("service", serviceName)
            ).list(r -> {
                String src = r.get("srcLabels").asList() + " " + r.get("srcProps").asMap();
                if (r.get("relType").isNull()) {
                    return src + " (no connected dependencies)";
                }
                String arrow = r.get("isOutgoing").asBoolean()
                        ? " -[" + r.get("relType").asString() + "]-> "
                        : " <-[" + r.get("relType").asString() + "]- ";
                return src + arrow + r.get("neighborLabels").asList() + " " + r.get("neighborProps").asMap();
            });

            if (lines.isEmpty()) {
                return "No existing node found with name '" + serviceName + "'. Call listAllServices to see mapped nodes.";
            }
            return String.join("\n", lines);
        }
    }

    @Tool("List all infrastructure services, apps, and databases currently mapped in the Neo4j graph")
    public String listAllServices() {
        System.out.println("[TOOL] listAllServices called");
        try (Session session = neo4jDriver.session()) {
            List<String> nodes = session.run(
                    "MATCH (n) WHERE NOT n:Incident RETURN labels(n) AS labels, properties(n) AS props LIMIT 25"
            ).list(r -> r.get("labels").asList() + " " + r.get("props").asMap());
            return nodes.isEmpty() ? "Graph has no service nodes yet." : String.join("\n", nodes);
        }
    }

    @Tool("Fetch the 5 most recent historical incidents affecting a specific service")
    public String getRecentIncidents(@P("Exact name of the service") String serviceName) {
        System.out.println("[TOOL] getRecentIncidents called for: " + serviceName);
        try (Session session = neo4jDriver.session()) {
            List<String> history = session.run(
                    """
                    MATCH (i:Incident)-[:AFFECTS]->(s {name: $service})
                    RETURN i.incident AS incident, i.severity AS severity, toString(i.createdAt) AS createdAt
                    ORDER BY i.createdAt DESC
                    LIMIT 5
                    """,
                    Values.parameters("service", serviceName)
            ).list(r -> r.get("createdAt").asString() + " | " + r.get("severity").asString() + " | " + r.get("incident").asString());
            return history.isEmpty() ? "No prior linked incidents for " + serviceName : String.join("\n", history);
        }
    }

    @Tool("Create an Incident node in Neo4j Aura and link it with an AFFECTS relationship to the affected service or database node")
    public String recordAndLinkIncident(
            @P("Affected service or component name") String serviceName,
            @P("Short incident type or title") String incidentType,
            @P("Severity level such as CRITICAL, HIGH, MEDIUM, or LOW") String severity,
            @P("Concise root-cause and blast-radius analysis") String analysis,
            @P("Actionable remediation steps for the on-call engineer") String remediationPlan) {

        System.out.println("[TOOL] recordAndLinkIncident called for: " + serviceName);
        try (Session session = neo4jDriver.session()) {
            List<Record> linked = session.run(
                    """
                    MATCH (target {name: $service})
                    WHERE NOT target:Incident
                    WITH target LIMIT 1
                    CREATE (i:Incident {
                        service: $service,
                        incident: $incident,
                        severity: $severity,
                        analysis: $analysis,
                        remediationPlan: $remediationPlan,
                        createdAt: datetime()
                    })
                    MERGE (i)-[:AFFECTS]->(target)
                    RETURN elementId(i) AS id, labels(target) AS targetLabels, target.name AS targetName
                    """,
                    Values.parameters(
                            "service", serviceName,
                            "incident", incidentType,
                            "severity", severity,
                            "analysis", analysis,
                            "remediationPlan", remediationPlan
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
                            analysis: $analysis,
                            remediationPlan: $remediationPlan,
                            createdAt: datetime()
                        })
                        MERGE (i)-[:AFFECTS]->(target)
                        RETURN elementId(i) AS id, labels(target) AS targetLabels, target.name AS targetName
                        """,
                        Values.parameters(
                                "service", serviceName,
                                "incident", incidentType,
                                "severity", severity,
                                "analysis", analysis,
                                "remediationPlan", remediationPlan
                        )
                ).single();
            }

            String result = "Saved Incident " + saved.get("id").asString()
                    + " -[:AFFECTS]-> " + saved.get("targetLabels").asList()
                    + " (" + saved.get("targetName").asString() + ")";
            System.out.println("[TOOL] " + result);
            return result;
        }
    }

    @Override
    public void destroy() {
        neo4jDriver.close();
    }
}
