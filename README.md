# Automated Incident Response System

An event-driven **Multi-Agent SRE Incident Response System** built with Java, Spring Boot, LangChain4j, Neo4j Aura, and Vertex AI Gemini on Google Cloud. When a system alert arrives, a **Detective Agent** queries live infrastructure topology and incident history in Neo4j via LangChain4j `@Tool` calls, then hands off to a **Responder Agent** that generates a remediation plan and persists a linked `(:Incident)-[:AFFECTS]->` node back into the graph.

## Architecture

```text
Alert (JSON) ──▶ Cloud Pub/Sub (system-incidents)
                       │  Authenticated Push (OIDC JWT + --push-no-wrapper)
                       ▼
                 Cloud Run (Spring Boot /api/webhook — IAM Protected)
                  ├──▶ DetectiveAgent (LangChain4j + Gemini 3.5 Flash Lite)
                  │       └──▶ @Tool: getServiceBlastRadius / getRecentIncidents (Neo4j Aura)
                  └──▶ ResponderAgent (LangChain4j + Gemini 3.5 Flash Lite)
                          └──▶ @Tool: recordAndLinkIncident ──▶ (:Incident)-[:AFFECTS]->(:Database/:Service)
```

## Tech Stack

* **Application:** Java 17, Spring Boot 4.1.2 (Maven)
* **AI / Multi-Agent:** LangChain4j `AiServices` (`@Tool`) + `langchain4j-google-genai` (Vertex AI Gemini `gemini-3.5-flash-lite`)
* **Graph Database:** Neo4j Aura + Neo4j Java Driver 5.14.0
* **Cloud Infrastructure:** Google Cloud Run (IAM-locked), Cloud Pub/Sub (OIDC Push), Secret Manager, Cloud Build, Artifact Registry

## Completed Milestones

* [x] Core GCP setup (APIs, Artifact Registry, Secret Manager, IAM roles)
* [x] Spring Boot webhook (`POST /api/webhook`) deployed to Cloud Run via Buildpacks
* [x] Cloud Pub/Sub push subscription delivering raw JSON payloads (`--push-no-wrapper`)
* [x] Neo4j Aura authentication via Secret Manager (`SPRING_NEO4J_AUTHENTICATION_PASSWORD`)
* [x] Multi-agent tool calling (`DetectiveAgent` + `ResponderAgent`) with `langchain4j-google-genai`
* [x] Automated `(:Incident)-[:AFFECTS]->` graph linking for blast-radius visualization
* [x] Secured `/api/webhook` with authenticated Pub/Sub push (OIDC / IAM)

---

## Replicate This Setup

### Prerequisites
* A Google Cloud project with billing enabled and `gcloud` CLI configured
* A [Neo4j Aura](https://console.neo4j.io) instance with sample topology nodes (e.g., `(:Service {name: 'FrontendApp'})-[:DEPENDS_ON]->(:Database {name: 'UserDB'})`)

### 1. Enable APIs & Grant Vertex AI Permission
```bash
export PROJECT_ID="<YOUR_GCP_PROJECT_ID>"
export REGION="asia-south1"
export PROJECT_NUMBER=$(gcloud projects describe$PROJECT_ID --format="value(projectNumber)")

gcloud config set project $PROJECT_ID

gcloud services enable \
  run.googleapis.com \
  pubsub.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  aiplatform.googleapis.com \
  secretmanager.googleapis.com

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role="roles/aiplatform.user"
```

### 2. Store Your Neo4j Password in Secret Manager
```bash
read -s -p "Enter Neo4j Aura password: " NEO4J_PW && echo
printf "%s" "$NEO4J_PW" | gcloud secrets create neo4j-password --data-file=- && unset NEO4J_PW

gcloud secrets add-iam-policy-binding neo4j-password \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

### 3. Configure & Deploy Locked-Down Cloud Run Service
1. Update `spring.neo4j.uri` in `src/main/resources/application.properties` (never store passwords in this file).
2. Update `.projectId(...)` in `src/main/java/com/example/demo/IncidentController.java`.
3. Deploy with public access disabled:
```bash
gcloud run deploy agentic-response-app \
  --source . \
  --region $REGION \
  --no-allow-unauthenticated \
  --set-build-env-vars="GOOGLE_RUNTIME_VERSION=17" \
  --set-secrets="SPRING_NEO4J_AUTHENTICATION_PASSWORD=neo4j-password:latest"
```

### 4. Wire Authenticated Pub/Sub Push (OIDC)
```bash
export INVOKER_SA="pubsub-invoker@${PROJECT_ID}.iam.gserviceaccount.com"
export SERVICE_URL=$(gcloud run services describe agentic-response-app --region$REGION --format="value(status.url)")

gcloud iam service-accounts create pubsub-invoker --display-name="Pub/Sub Cloud Run Invoker"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountTokenCreator"

gcloud run services add-iam-policy-binding agentic-response-app \
  --region $REGION \
  --member="serviceAccount:${INVOKER_SA}" \
  --role="roles/run.invoker"

gcloud pubsub topics create system-incidents

gcloud pubsub subscriptions create system-incidents-sub \
  --topic=system-incidents \
  --push-endpoint="${SERVICE_URL}/api/webhook" \
  --push-no-wrapper \
  --push-auth-service-account="${INVOKER_SA}"
```

### 5. Test End-to-End & Visualize in Neo4j Aura
```bash
# Publish a test incident alert
gcloud pubsub topics publish system-incidents \
  --message='{"service":"UserDB","incident":"connection-pool-exhausted","errorRate":"42%","severity":"CRITICAL"}'

# Inspect multi-agent tool execution in Cloud Run logs
sleep 20
gcloud run services logs read agentic-response-app --region $REGION --limit=40
```

Visualize the incident and blast radius in **Neo4j Aura Query**:
```cypher
MATCH p = (i:Incident)-[:AFFECTS]->(target)
OPTIONAL MATCH dep = (target)-[r]-(neighbor)
RETURN p, dep
```
