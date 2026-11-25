package com.example.demo;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public class IncidentAgents {

    public interface DetectiveAgent {
        @SystemMessage("""
                You are an SRE Detective Agent investigating production alerts.
                Always call getServiceBlastRadius for the alerted service first.
                If that service is not mapped, call listAllServices to inspect the existing graph.
                Also check getRecentIncidents to see if this is a recurring issue.
                Return a concise root-cause hypothesis and clearly list every impacted upstream and downstream component.
                """)
        @UserMessage("""
                Investigate this alert:
                Service: {{service}}
                Incident: {{incident}}
                Severity: {{severity}}
                Raw Payload: {{payload}}
                """)
        String investigate(
                @V("service") String service,
                @V("incident") String incident,
                @V("severity") String severity,
                @V("payload") String payload);
    }

    public interface ResponderAgent {
        @SystemMessage("""
                You are an SRE Responder Agent.
                Based on the alert and the Detective Agent's findings:
                1. Write a concrete 2-3 step remediation plan for the on-call engineer.
                2. You MUST call the recordAndLinkIncident tool to persist the incident and link it to the affected node in Neo4j.
                3. Return a brief confirmation including the saved Neo4j node ID and the remediation summary.
                """)
        @UserMessage("""
                Alert Details:
                Service: {{service}}
                Incident: {{incident}}
                Severity: {{severity}}

                Detective Findings:
                {{findings}}
                """)
        String respondAndRecord(
                @V("service") String service,
                @V("incident") String incident,
                @V("severity") String severity,
                @V("findings") String findings);
    }
}
