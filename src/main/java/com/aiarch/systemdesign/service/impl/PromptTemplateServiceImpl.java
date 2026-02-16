package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.service.PromptTemplateService;
import org.springframework.stereotype.Service;

@Service
public class PromptTemplateServiceImpl implements PromptTemplateService {

    @Override
    public String sowPrompt(DesignRequestDTO request) {
        return """
                You are a principal solutions architect and delivery lead.
                Generate a professional Scope of Work (SOW) for this project.
                Product Name: %s
                Functional Requirements: %s
                Non-Functional Requirements: %s
                Expected DAU: %s
                Region: %s
                Scale: %s
                Target Platform: %s
                Design Domain: %s
                Preferred Stack: %s
                Preferred Database: %s
                Server Type: %s
                Container Strategy: %s

                Requirements:
                1) Write an implementation-focused SOW suitable for client/engineering alignment.
                2) Include scope boundaries, deliverables, milestones, acceptance criteria, and risks.
                3) Be concrete and avoid generic statements.

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "sow": {
                    "project_summary": "string",
                    "in_scope": ["string"],
                    "out_of_scope": ["string"],
                    "deliverables": ["string"],
                    "milestones": ["string"],
                    "acceptance_criteria": ["string"],
                    "risks": ["string"],
                    "assumptions": ["string"]
                  }
                }
                """.formatted(
                request.getProductName(),
                request.getFunctionalRequirements(),
                request.getNonFunctionalRequirements() == null || request.getNonFunctionalRequirements().isEmpty()
                        ? "Not specified"
                        : request.getNonFunctionalRequirements(),
                request.getExpectedDAU(),
                request.getRegion(),
                request.getScale(),
                request.getTargetPlatform(),
                request.getDesignDomain(),
                request.getTechStackChoice(),
                request.getDatabaseChoice(),
                request.getServerType(),
                request.getContainerStrategy()
        );
    }

    @Override
    public String hldPrompt(DesignRequestDTO request) {
        return """
                You are a principal system architect.
                Create a production-grade, in-depth high-level architecture for the following product.
                Product Name: %s
                Functional Requirements: %s
                Non-Functional Requirements: %s
                Expected DAU: %s
                Region: %s
                Scale: %s
                Target Platform: %s
                Design Domain: %s
                Preferred Stack: %s
                Preferred Database: %s
                Server Type: %s
                Container Strategy: %s

                Depth requirements:
                1) Cover how EACH functional requirement is handled by specific components.
                2) Include reliability, latency, availability, consistency, and security assumptions.
                3) Use realistic components such as: API Gateway, Load Balancer, Auth Service, User Service,
                   Feed Service, Media Service, Notification Service, Search Service, Rate Limiter, Cache,
                   Message Queue/Event Bus, Worker, Object Storage, CDN, SQL/NoSQL, Observability stack.
                4) Keep architecture text practical and implementation-oriented, not generic.
                5) Include at least 20 API contracts spanning auth, user/profile, core business objects,
                   search/feed, notification, admin/ops, health/metrics, and integration endpoints.
                6) Include dedicated design viewpoints inspired by backend, software architect,
                   devops, and docker roadmaps.
                7) Tailor the architecture and implementation choices strongly for the selected
                   target platform, design domain, and preferred stack.
                8) If design domain is MOBILE, include BFF/API optimization, offline sync, push notifications,
                   and mobile telemetry.
                9) If design domain is FRONTEND, include rendering strategy, state management, caching strategy,
                   asset delivery, and observability.
                10) If design domain is DEVOPS or SERVER_ARCHITECTURE, include deployment topology, CI/CD,
                   infra automation, runtime operations, security controls, and SLO governance.

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "overview": "string",
                  "assumptions": ["string"],
                  "capacity_estimation": "string",
                  "hld": "string",
                  "api_contracts": [
                    {
                      "name": "string",
                      "method": "string",
                      "path": "string",
                      "request_schema": "string",
                      "response_schema": "string",
                      "error_codes": ["string"]
                    }
                  ],
                  "database_schemas": [
                    {
                      "entity_name": "string",
                      "fields": ["string"],
                      "indexes": ["string"]
                    }
                  ],
                  "backend_architecture": "string",
                  "software_architecture": "string",
                  "devops_strategy": "string",
                  "docker_strategy": "string",
                  "tradeoffs": "string"
                }
                """.formatted(
                request.getProductName(),
                request.getFunctionalRequirements(),
                request.getNonFunctionalRequirements() == null || request.getNonFunctionalRequirements().isEmpty()
                        ? "Not specified"
                        : request.getNonFunctionalRequirements(),
                request.getExpectedDAU(),
                request.getRegion(),
                request.getScale(),
                request.getTargetPlatform(),
                request.getDesignDomain(),
                request.getTechStackChoice(),
                request.getDatabaseChoice(),
                request.getServerType(),
                request.getContainerStrategy()
        );
    }

    @Override
    public String componentBreakdownPrompt(String hldJson) {
        return """
                Use the HLD JSON below as source context and generate a deep component breakdown.
                HLD_JSON:
                %s

                Depth requirements:
                1) Include all core runtime components: edge, api, services, async workers, data stores, cache, messaging.
                2) For each component, provide detailed responsibility and explicit dependencies.
                3) Describe cross-cutting components as needed: auth, observability, config, rate limiting.
                4) Ensure components map back to functional requirements.
                5) Include execution order (build_order) for implementation sequencing and
                   implementation_approach to explain how each module should be built.
                6) If the product has mobile context, include mobile-specific modules:
                   auth/onboarding, profile/session, feed/listing, local storage/offline sync,
                   push notification integration, analytics/telemetry, and crash reporting.

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "components": [
                    {
                      "name":"string",
                      "type":"string",
                      "responsibility":"string",
                      "build_order":1,
                      "implementation_approach":"string",
                      "dependencies":["string"]
                    }
                  ]
                }
                """.formatted(hldJson);
    }

    @Override
    public String lldPrompt(String componentBreakdownJson) {
        return """
                Based on the component breakdown JSON below, produce low level design with implementation detail.
                COMPONENT_BREAKDOWN_JSON:
                %s

                Depth requirements:
                1) For each component, include internal module responsibilities, classes/interfaces, and interaction sequence.
                2) Include data structures, idempotency/retry concepts, and consistency implications where relevant.
                3) Prefer realistic backend design patterns over generic placeholders.

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "lld": [
                    {
                      "component_name":"string",
                      "module_description":"string",
                      "classes":["string"],
                      "interfaces":["string"],
                      "sequence":["string"]
                    }
                  ]
                }
                """.formatted(componentBreakdownJson);
    }

    @Override
    public String dataFlowPrompt(String hldJson, String lldJson) {
        return """
                Use both HLD and LLD JSON to generate detailed request/response and async data flow scenarios.
                HLD_JSON:
                %s
                LLD_JSON:
                %s

                Depth requirements:
                1) Include end-to-end flows for read path, write path, and at least one asynchronous/event-driven flow.
                2) Mention where cache, queue, database, and external interfaces are touched in each scenario.
                3) Steps should be concrete and sequential.

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "data_flow_scenarios": [
                    {"name":"string","trigger":"string","steps":["string"],"expected_outcome":"string"}
                  ]
                }
                """.formatted(hldJson, lldJson);
    }

    @Override
    public String scalingStrategyPrompt(String hldJson) {
        return """
                Use the HLD JSON and produce an in-depth scaling strategy.
                HLD_JSON:
                %s

                Depth requirements:
                1) Include horizontal and vertical scaling, partitioning/sharding, caching, and queue backpressure handling.
                2) Include regional strategy (single vs multi-region) and database scaling approach.
                3) Include practical thresholds and rollout strategy.
                4) Minimum 10 concise bullet-like recommendations in a single coherent string.

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "scaling_strategy": "string"
                }
                """.formatted(hldJson);
    }

    @Override
    public String failureHandlingPrompt(String hldJson) {
        return """
                Use the HLD JSON and produce an in-depth failure handling strategy.
                HLD_JSON:
                %s

                Depth requirements:
                1) Cover failure modes at gateway, service, queue, cache, and database layers.
                2) Include detection, mitigation, fallback/degradation, retry/circuit-breaker, and recovery strategy.
                3) Include DR/backup considerations and operational runbook orientation.
                4) Minimum 10 concise bullet-like recommendations in a single coherent string.

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "failure_handling": "string"
                }
                """.formatted(hldJson);
    }

    @Override
    public String diagramMetadataPrompt(String hldJson, String lldJson) {
        return """
                Produce detailed diagram metadata from HLD and LLD JSON.
                HLD_JSON:
                %s
                LLD_JSON:
                %s

                Diagram requirements:
                1) Include nodes for client, edge/cdn, gateway, auth, core services, async workers, data stores, cache, queue, and observability.
                2) Ensure edges reflect realistic protocols (HTTPS/gRPC, SQL, Redis, Kafka/SQS, etc.).
                3) Keep graph connected and coherent; avoid isolated nodes.
                4) Use stable IDs in kebab-case.
                5) Add node labels, technologies, and brief descriptions.
                6) Add rough x/y positions so frontend can render a clean architecture layout.
                7) Include a mermaid flowchart string for quick visual rendering.

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema exactly:
                {
                  "nodes": [
                    {
                      "id": "api-gateway",
                      "type": "gateway",
                      "position": { "x": 520, "y": 220 },
                      "data": {
                        "label": "API Gateway",
                        "technology": "Nginx / Kong",
                        "description": "Single entry point and auth routing",
                        "layer": "EDGE"
                      }
                    }
                  ],
                  "edges": [
                    { "from": "api-gateway", "to": "user-service", "label": "REST" },
                    { "source": "user-service", "target": "postgresql", "label": "SQL" }
                  ],
                  "mermaid": "flowchart LR\\nclient[Client] --> gateway[API Gateway]"
                }
                """.formatted(hldJson, lldJson);
    }

    @Override
    public String taskBreakdownPrompt(String hldJson, String componentBreakdownJson, String lldJson) {
        return """
                Build an implementation plan from the provided design context.
                HLD_JSON:
                %s
                COMPONENT_BREAKDOWN_JSON:
                %s
                LLD_JSON:
                %s

                Requirements:
                1) Provide complete module-wise execution plan with realistic engineering tasks.
                2) Keep modules in delivery order to show what should be built first, next, and later.
                3) For each module include implementation approach and a detailed task-level plan.
                4) Estimate effort in HOURS for:
                   - experienced developer
                   - mid level developer
                   - fresher
                5) If this is mobile-oriented, include mobile client modules, API integration,
                   local persistence/offline sync, notification integration, release pipeline,
                   and QA/sign-off tasks.
                6) Each module must have at least 6 task rows and each task row should include
                   task-level hours for all 3 developer levels.

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "task_breakdown": [
                    {
                      "module_name": "string",
                      "implementation_approach": "string",
                      "tasks": [
                        {
                          "task_name": "string",
                          "description": "string",
                          "hours_experienced_developer": 0,
                          "hours_mid_level_developer": 0,
                          "hours_fresher": 0
                        }
                      ],
                      "hours_experienced_developer": 0,
                      "hours_mid_level_developer": 0,
                      "hours_fresher": 0
                    }
                  ]
                }
                """.formatted(hldJson, componentBreakdownJson, lldJson);
    }

    @Override
    public String wireframePrompt(String hldJson, String componentBreakdownJson, String lldJson) {
        return """
                Generate implementation-grade product wireframes from design context.
                HLD_JSON:
                %s
                COMPONENT_BREAKDOWN_JSON:
                %s
                LLD_JSON:
                %s

                Requirements:
                1) Provide wireframe screens in build order from onboarding/core flows to admin/ops screens.
                2) For each screen include purpose, layout description, UI components, interactions, and API bindings.
                3) Ensure wireframes reflect mobile/web context where relevant.

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "wireframe_summary": "string",
                  "screens": [
                    {
                      "screen_name": "string",
                      "platform": "string",
                      "purpose": "string",
                      "layout_description": "string",
                      "ui_components": ["string"],
                      "interactions": ["string"],
                      "api_bindings": ["string"]
                    }
                  ]
                }
                """.formatted(hldJson, componentBreakdownJson, lldJson);
    }

    @Override
    public String invalidJsonRetrySuffix() {
        return """
                
                Your previous response was invalid JSON.
                Return only valid JSON that matches the schema exactly.
                """;
    }
}
