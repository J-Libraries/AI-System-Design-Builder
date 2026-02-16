package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.service.PromptTemplateService;
import org.springframework.stereotype.Service;

@Service
public class PromptTemplateServiceImpl implements PromptTemplateService {

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

                Depth requirements:
                1) Cover how EACH functional requirement is handled by specific components.
                2) Include reliability, latency, availability, consistency, and security assumptions.
                3) Use realistic components such as: API Gateway, Load Balancer, Auth Service, User Service,
                   Feed Service, Media Service, Notification Service, Search Service, Rate Limiter, Cache,
                   Message Queue/Event Bus, Worker, Object Storage, CDN, SQL/NoSQL, Observability stack.
                4) Keep architecture text practical and implementation-oriented, not generic.
                5) Include at least 12 API contracts spanning auth, user/profile, core business objects,
                   search/feed, notification, admin/ops, and health/metrics endpoints.
                6) Include dedicated design viewpoints inspired by backend, software architect,
                   devops, and docker roadmaps.

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
                request.getScale()
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

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "components": [
                    {"name":"string","type":"string","responsibility":"string","dependencies":["string"]}
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
    public String invalidJsonRetrySuffix() {
        return """
                
                Your previous response was invalid JSON.
                Return only valid JSON that matches the schema exactly.
                """;
    }
}
