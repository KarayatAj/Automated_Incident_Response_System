# Automated Incident Response System

An event-driven **SRE Detective Agent** built with Java, Spring Boot, Neo4j Aura, and Vertex AI Gemini on Google Cloud. When a system alert is published, the service pulls live infrastructure context from a Neo4j graph, uses Gemini to generate a root-cause hypothesis and next steps, and records the incident back into the graph.

## Architecture

```text
Alert (JSON) ──▶ Cloud Pub/Sub (system-incidents)
                       │  Push (--push-no-wrapper)
                       ▼
                 Cloud Run (Spring Boot /api/webhook)
                  ├──▶ Neo4j Aura (Fetch topology context)
                  ├──▶ Vertex AI Gemini (Triage & root-cause analysis)
                  └──▶ Neo4j Aura (Persist :Incident node)
```

## Tech Stack

* **Application:** Java 17, Spring Boot 4.1.2 (Maven)
* **AI / LLM:** LangChain4j + Vertex AI Gemini (`gemini-3.5-flash-lite`)
* **Graph Database:** Neo4j Aura + Neo4j Java Driver 5.14.0
* **Cloud Infrastructure:** Google Cloud Run, Cloud Pub/Sub, Secret Manager, Cloud Build, Artifact Registry

## Current Progress & Roadmap

* [x] Core GCP setup (APIs, Artifact Registry, Secret Manager, IAM roles)
* [x] Spring Boot webhook (`POST /api/webhook`) deployed to Cloud Run via Buildpacks
* [x] Cloud Pub/Sub push subscription delivering raw JSON payloads (`--push-no-wrapper`)
* [x] Neo4j Aura authentication via Secret Manager (`SPRING_NEO4J_AUTHENTICATION_PASSWORD`)
* [x] End-to-end graph context retrieval, Gemini incident triage, and `(:Incident)` persistence
* [ ] Link `(:Incident)-[:AFFECTS]->(:Service)` nodes for automated blast-radius visualization
* [ ] Secure `/api/webhook` with authenticated Pub/Sub push (OIDC / IAM)
* [ ] Multi-agent tool calling with `langchain4j-google-genai`

---

## Replicate This Setup

### Prerequisites
* A Google Cloud project with billing enabled and `gcloud` CLI configured
* A [Neo4j Aura](https://console.neo4j.io) instance (Free tier works) with sample service nodes (e.g., `FrontendApp`, `UserDB`)

### 1. Enable APIs & Grant IAM Permissions
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

# Allow Cloud Run to invoke Vertex AI Gemini
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

### 3. Configure the Application
1. Clone this repository and open `src/main/resources/application.properties`.
2. Update `spring.neo4j.uri` with your Neo4j Aura URI (do **not** store the password here—it is injected at runtime from Secret Manager).
3. Update the GCP project ID in `src/main/java/com/example/demo/IncidentController.java` inside `VertexAiGeminiChatModel.builder().project(...)`.

### 4. Deploy to Cloud Run
```bash
gcloud run deploy agentic-response-app \
  --source . \
  --region $REGION \
  --allow-unauthenticated \
  --set-build-env-vars="GOOGLE_RUNTIME_VERSION=17" \
  --set-secrets="SPRING_NEO4J_AUTHENTICATION_PASSWORD=neo4j-password:latest"
```

### 5. Connect Pub/Sub Push & Test End-to-End
```bash
SERVICE_URL=$(gcloud run services describe agentic-response-app \
  --region $REGION \
  --format="value(status.url)")

gcloud pubsub topics create system-incidents

gcloud pubsub subscriptions create system-incidents-sub \
  --topic=system-incidents \
  --push-endpoint="${SERVICE_URL}/api/webhook" \
  --push-no-wrapper

# Publish a test incident alert
gcloud pubsub topics publish system-incidents \
  --message='{"service":"checkout-api","incident":"high-latency","errorRate":"18%","severity":"CRITICAL"}'

# Inspect Cloud Run logs after ~15 seconds
gcloud run services logs read agentic-response-app --region $REGION --limit=30
```

Verify the saved triage result in **Neo4j Aura Query**:
```cypher
MATCH (i:Incident)
RETURN i.createdAt, i.payload, i.analysis
ORDER BY i.createdAt DESC
LIMIT 5
```