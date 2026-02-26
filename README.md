# AI System Design Builder — Backend (Server)

> An AI-powered platform that generates complete system designs, SOW documents, MVP scopes, API contracts, wireframes, and task breakdowns from a single prompt — powered by **Sarvam AI** (`sarvam-m` model).

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
  - [1. Clone the Repository](#1-clone-the-repository)
  - [2. Set Up PostgreSQL](#2-set-up-postgresql)
  - [3. Configure Application Properties](#3-configure-application-properties)
  - [4. Build & Run](#4-build--run)
- [Client Setup](#client-setup)
- [API Reference](#api-reference)
  - [Design Endpoints](#design-endpoints)
  - [Project Endpoints](#project-endpoints)
- [WebSocket / Real-Time Events](#websocket--real-time-events)
- [Design Request Fields](#design-request-fields)
- [Environment Profiles](#environment-profiles)
- [Configuration Reference](#configuration-reference)
- [License](#license)

---

## Overview

The **AI System Design Builder** backend is a Spring Boot application that acts as the AI orchestration layer for the platform. Given a product description and a set of requirements, the server:

1. Calls the **Sarvam AI** (`sarvam-m`) large-language model to generate a multi-stage system design.
2. Produces a structured `SystemDesignDocument` containing:
   - High-level architecture diagram (nodes & edges)
   - Low-level component designs (LLD)
   - Database schema
   - API contracts
   - Statement of Work (SOW)
   - Task breakdown (exportable as CSV)
   - UI wireframes (iterable via chat)
3. Persists the design in **PostgreSQL** (JSONB columns via Hibernate).
4. Streams live progress back to the frontend via **WebSocket (STOMP/SockJS)**.
5. Exposes REST endpoints to retrieve, update, regenerate, and export designs as **PDF** or **CSV**.

---

## Architecture

```
┌─────────────────────────────────────┐
│           React Client              │  ← AI-System-Design-Builder-Client
│  (Vite + React + SockJS/STOMP)      │
└──────────┬──────────────────────────┘
           │  REST + WebSocket (ws://)
┌──────────▼──────────────────────────┐
│      Spring Boot Backend (this)     │
│  ┌──────────────────────────────┐   │
│  │  DesignController            │   │  REST API  /api/design/**
│  │  ProjectController           │   │  REST API  /api/projects/**
│  └──────────┬───────────────────┘   │
│             │                       │
│  ┌──────────▼───────────────────┐   │
│  │  DesignOrchestratorService   │   │  Async multi-stage AI pipeline
│  │  AIStageService              │   │
│  │  PromptTemplateService       │   │
│  └──────────┬───────────────────┘   │
│             │                       │
│  ┌──────────▼───────────────────┐   │
│  │  SarvamClient                │   │  Calls Sarvam AI REST API
│  └──────────────────────────────┘   │
│                                     │
│  ┌──────────────────────────────┐   │
│  │  PostgreSQL (JSONB storage)  │   │
│  └──────────────────────────────┘   │
└─────────────────────────────────────┘
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 4.0.x |
| ORM | Spring Data JPA + Hibernate |
| Database | PostgreSQL (≥ 14) |
| AI Provider | Sarvam AI (`sarvam-m`) |
| HTTP Client | Spring WebFlux `WebClient` |
| Real-time | WebSocket (STOMP over SockJS) |
| PDF Export | OpenPDF 2.0.3 |
| Mapping | MapStruct 1.6.3 |
| Boilerplate | Lombok |
| Build Tool | Maven (Maven Wrapper included) |

---

## Project Structure

```
systemdesign/
├── src/
│   └── main/
│       ├── java/com/aiarch/systemdesign/
│       │   ├── SystemdesignApplication.java     # Entry point
│       │   ├── ai/
│       │   │   └── SarvamClient.java            # Sarvam AI HTTP client
│       │   ├── config/
│       │   │   ├── AsyncConfig.java             # Thread pool for async AI calls
│       │   │   ├── DataMigrationConfig.java
│       │   │   ├── JacksonConfig.java
│       │   │   ├── SarvamProperties.java        # AI config binding
│       │   │   ├── WebClientConfig.java         # WebClient bean
│       │   │   ├── WebConfig.java               # CORS configuration
│       │   │   └── WebSocketConfig.java         # STOMP/SockJS endpoint
│       │   ├── controller/
│       │   │   ├── DesignController.java        # /api/design endpoints
│       │   │   └── ProjectController.java       # /api/projects endpoints
│       │   ├── dto/                             # Request/response DTOs & enums
│       │   │   └── document/                   # Nested document structure DTOs
│       │   ├── exception/                       # Global error handling
│       │   ├── mapper/                          # MapStruct mapper
│       │   ├── model/                           # JPA entities (Project, SystemDesign)
│       │   ├── orchestrator/
│       │   │   └── AiOrchestratorService.java
│       │   ├── repository/                      # Spring Data JPA repos
│       │   └── service/                         # Business logic & AI pipeline
│       └── resources/
│           ├── application.properties           # Production config
│           └── application.dev.properties       # Development config (empty API key)
├── pom.xml
└── mvnw / mvnw.cmd                              # Maven wrapper
```

---

## Prerequisites

| Requirement | Minimum Version |
|---|---|
| Java (JDK) | 17 |
| Maven | 3.8+ (or use included `./mvnw`) |
| PostgreSQL | 14+ |
| Sarvam AI API Key | — (obtain from [sarvam.ai](https://sarvam.ai)) |
| Node.js *(for Client)* | 18+ |

---

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/J-Libraries/AI-System-Design-Builder.git
cd AI-System-Design-Builder/systemdesign
```

### 2. Set Up PostgreSQL

Create the database and a dedicated user:

```sql
-- Connect as a superuser (e.g. psql -U postgres)
CREATE USER ai_system_design_user WITH PASSWORD '123456';
CREATE DATABASE ai_system_design OWNER ai_system_design_user;
GRANT ALL PRIVILEGES ON DATABASE ai_system_design TO ai_system_design_user;
```

> **Note:** The application uses `spring.jpa.hibernate.ddl-auto=update`, so Hibernate will automatically create and migrate the schema on first run. No manual schema setup is needed.

### 3. Configure Application Properties

Open `src/main/resources/application.properties` and fill in your values:

```properties
# ===============================
# DATABASE CONFIGURATION
# ===============================
spring.datasource.url=jdbc:postgresql://localhost:5432/ai_system_design
spring.datasource.username=ai_system_design_user
spring.datasource.password=123456

# ===============================
# SARVAM AI CONFIGURATION
# ===============================
sarvam.api-key=YOUR_SARVAM_API_KEY_HERE
sarvam.base-url=https://api.sarvam.ai/v1/chat/completions
sarvam.model=sarvam-m
sarvam.max-tokens=4096
```

> ⚠️ **Important:** Never commit a real API key to version control. Use environment variables or a secrets manager in production (see [Environment Profiles](#environment-profiles)).

### 4. Build & Run

**Using the Maven Wrapper (recommended):**

```bash
# macOS / Linux
./mvnw spring-boot:run

# Windows
mvnw.cmd spring-boot:run
```

**Or build a JAR and run it:**

```bash
./mvnw clean package -DskipTests
java -jar target/systemdesign-0.0.1-SNAPSHOT.jar
```

The server starts on **`http://localhost:8080`** by default.

---

## Client Setup

The frontend React client is a separate repository:

```bash
git clone https://github.com/J-Libraries/AI-System-Design-Builder-Client.git
cd AI-System-Design-Builder-Client

# Install dependencies
npm install

# Start the development server
npm run dev
```

The client connects to the backend at `http://localhost:8080`. Make sure the backend is running first.

> The Figma source for the UI design is available at:  
> [https://www.figma.com/design/S0kmKOi4nu7NuLZ7nIrO9K/AI-System-Design-Platform](https://www.figma.com/design/S0kmKOi4nu7NuLZ7nIrO9K/AI-System-Design-Platform)

---

## API Reference

### Design Endpoints

Base path: `/api/design`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/design` | List all saved designs (summary) |
| `POST` | `/api/design/generate` | Generate a new system design (async) |
| `POST` | `/api/design/{id}/regenerate` | Regenerate an existing design |
| `GET` | `/api/design/{id}/request` | Get the original request for a design |
| `DELETE` | `/api/design/{id}` | Delete a design |
| `GET` | `/api/design/{id}/document` | Get the full `SystemDesignDocument` (JSON) |
| `PUT` | `/api/design/{id}/document` | Update / save edits to a document |
| `GET` | `/api/design/{id}/export/pdf` | Export as PDF |
| `GET` | `/api/design/{id}/export/sow/pdf` | Export the SOW section as PDF |
| `GET` | `/api/design/{id}/export/task-breakdown/csv` | Export the task breakdown as CSV |
| `POST` | `/api/design/{id}/wireframe/iterate` | Iterate wireframe via AI chat prompt |

#### Generate Design — Request Body

```json
POST /api/design/generate
Content-Type: application/json

{
  "productName": "TechFlow",
  "functionalRequirements": [
    "User authentication and authorization",
    "Real-time notifications",
    "Dashboard with analytics"
  ],
  "nonFunctionalRequirements": [
    "99.9% uptime SLA",
    "< 200ms API response time"
  ],
  "expectedDAU": 50000,
  "region": "us-east-1",
  "scale": "MEDIUM",
  "targetPlatform": "WEB",
  "designDomain": "BACKEND",
  "techStackChoice": "Node.js, React",
  "databaseChoice": "PostgreSQL",
  "serverType": "REST",
  "containerStrategy": "Docker + Kubernetes"
}
```

#### Generate Design — Response

```json
HTTP 202 Accepted

{
  "designId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PROCESSING"
}
```

Use the `designId` to subscribe to WebSocket updates and later retrieve the completed document.

#### Wireframe Iteration — Request Body

```json
POST /api/design/{id}/wireframe/iterate
Content-Type: application/json

{
  "prompt": "Make the dashboard darker with a sidebar navigation"
}
```

---

### Project Endpoints

Base path: `/api/projects`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/projects` | Create a new project |
| `GET` | `/api/projects` | List all projects |
| `GET` | `/api/projects/{id}` | Get a specific project |
| `POST` | `/api/projects/{id}/generate-hld` | Generate a high-level architecture for a project |

---

## WebSocket / Real-Time Events

The backend uses **STOMP over SockJS** to stream generation progress to the client.

| Property | Value |
|---|---|
| Connection URL | `http://localhost:8080/ws` |
| Protocol | SockJS + STOMP |
| Subscribe topic | `/topic/design/{designId}` |

### Event Types (`GenerationStatus`)

| Status | Meaning |
|---|---|
| `PROCESSING` | AI pipeline is running |
| `STAGE_COMPLETE` | One stage (e.g., HLD, API, SOW) has finished |
| `COMPLETE` | Full design generation finished |
| `ERROR` | Generation failed |

**Example (JavaScript client):**

```javascript
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

const client = new Client({
  webSocketFactory: () => new SockJS('http://localhost:8080/ws'),
  onConnect: () => {
    client.subscribe(`/topic/design/${designId}`, (message) => {
      const event = JSON.parse(message.body);
      console.log(event.status, event.stage, event.data);
    });
  },
});
client.activate();
```

---

## Design Request Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `productName` | `String` | ✅ | Name of the product / system |
| `functionalRequirements` | `List<String>` | ✅ | Feature list (at least 1) |
| `nonFunctionalRequirements` | `List<String>` | ❌ | Performance / reliability requirements |
| `expectedDAU` | `Long` | ✅ | Expected daily active users (> 0) |
| `region` | `String` | ✅ | Deployment region (e.g. `us-east-1`) |
| `scale` | `DesignScale` | ✅ | `SMALL`, `MEDIUM`, `LARGE`, `HYPERSCALE` |
| `targetPlatform` | `TargetPlatform` | ✅ | `WEB`, `MOBILE`, `BOTH` |
| `designDomain` | `DesignDomain` | ✅ | `MOBILE`, `BACKEND`, `FRONTEND`, `SERVER_ARCHITECTURE`, `DEVOPS` |
| `techStackChoice` | `String` | ✅ | Preferred technologies (e.g. `React, Node.js`) |
| `databaseChoice` | `String` | ✅ | Database preference (e.g. `PostgreSQL`) |
| `serverType` | `String` | ✅ | API style (e.g. `REST`, `GraphQL`) |
| `containerStrategy` | `String` | ✅ | Container/deploy strategy (e.g. `Docker + K8s`) |

---

## Environment Profiles

You can use a separate `application.dev.properties` profile for local development with a blank API key:

```bash
./mvnw spring-boot:run -Dspring.profiles.active=dev
```

For production deployments, override sensitive values via **environment variables**:

```bash
export SARVAM_API_KEY=sk_your_key_here
export SPRING_DATASOURCE_PASSWORD=secure_password
java -jar target/systemdesign-0.0.1-SNAPSHOT.jar
```

Or via Docker:

```bash
docker run -e SARVAM_API_KEY=sk_... \
           -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/ai_system_design \
           -e SPRING_DATASOURCE_USERNAME=ai_system_design_user \
           -e SPRING_DATASOURCE_PASSWORD=secure_password \
           -p 8080:8080 \
           ai-system-design-backend:latest
```

---

## Configuration Reference

All configurable keys in `application.properties`:

| Key | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/ai_system_design` | PostgreSQL JDBC URL |
| `spring.datasource.username` | `ai_system_design_user` | DB username |
| `spring.datasource.password` | `123456` | DB password |
| `spring.jpa.hibernate.ddl-auto` | `update` | Schema strategy (`update`, `create`, `validate`) |
| `spring.jpa.show-sql` | `true` | Log SQL queries |
| `sarvam.api-key` | *(empty)* | **Required.** Your Sarvam AI API key |
| `sarvam.base-url` | `https://api.sarvam.ai/v1/chat/completions` | Sarvam API endpoint |
| `sarvam.model` | `sarvam-m` | Model to use |
| `sarvam.max-tokens` | `4096` | Max tokens per completion |

---

## Related Repositories

| Repo | Description |
|---|---|
| **This repo** | Spring Boot backend (AI orchestration, REST API, WebSocket) |
| [AI-System-Design-Builder-Client](https://github.com/J-Libraries/AI-System-Design-Builder-Client) | React + Vite frontend client |

---

## License

This project is licensed under the terms in the [LICENSE](./LICENSE) file.
