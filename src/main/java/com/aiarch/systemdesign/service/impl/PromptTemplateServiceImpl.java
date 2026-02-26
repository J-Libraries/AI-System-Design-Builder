package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.service.PromptTemplateService;
import org.springframework.stereotype.Service;

@Service
public class PromptTemplateServiceImpl implements PromptTemplateService {

  private boolean isNoneSelected(String value) {
    if (value == null) {
      return true;
    }
    String normalized = value.trim().toLowerCase();
    return normalized.isBlank()
        || normalized.equals("none")
        || normalized.equals("none (not used)")
        || normalized.equals("not used")
        || normalized.equals("n/a");
  }

  @Override
  public String sowPrompt(DesignRequestDTO request) {
    String techStack = isNoneSelected(request.getTechStackChoice()) ? "Not used" : request.getTechStackChoice();
    String database = isNoneSelected(request.getDatabaseChoice()) ? "Not used" : request.getDatabaseChoice();
    String serverType = isNoneSelected(request.getServerType()) ? "Not used" : request.getServerType();
    String containerStrategy = isNoneSelected(request.getContainerStrategy()) ? "Not used"
        : request.getContainerStrategy();

    return """
        You are a principal solutions architect and delivery lead.
        Generate a professional, highly detailed Scope of Work (SOW) for this project.
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
        Stack Enabled: %s
        Database Enabled: %s
        Server Enabled: %s
        Container Strategy Enabled: %s

        Requirements:
        1) Write an implementation-focused SOW suitable for client and engineering sign-off.
        2) Be concrete and avoid generic filler text.
        3) Cover scope boundaries, deliverables, milestones, dependencies, acceptance criteria, and risks.
        4) Include measurable and testable statements.
        5) Make it execution-ready for planning, staffing, and handoff.
        6) Ensure depth:
           - project_summary should be 140-220 words
           - in_scope minimum 10 items
           - out_of_scope minimum 6 items
           - deliverables minimum 10 items
           - milestones minimum 8 items
           - acceptance_criteria minimum 10 items
           - risks minimum 8 items
           - assumptions minimum 8 items
        7) milestone format should include phase, owner, and completion condition in one sentence.
        8) acceptance_criteria should be objectively verifiable.
        9) risks should include impact + mitigation in each item.
        10) If any capability is marked as "Not used", do not include that capability in-scope or deliverables.
            Example: If server is not used, exclude server provisioning/ops deliverables.
        11) Apply exclusion matrix strictly:
            - Preferred Stack = Not used => exclude backend/service implementation deliverables and API-specific implementation scope.
            - Preferred Database = Not used => exclude schema, migration, indexing, backup/restore deliverables.
            - Server Type = Not used => exclude VM/K8s/server provisioning, runtime operations, host sizing.
            - Container Strategy = Not used => exclude Docker/container build and orchestration deliverables.

        Return ONLY valid JSON. No markdown. No comments. No additional text.
        Follow this exact schema:
        {
          "sow": {
            "project_summary": "string",
            "business_objectives": ["string"],
            "in_scope": ["string"],
            "out_of_scope": ["string"],
            "dependencies": ["string"],
            "deliverables": ["string"],
            "milestones": ["string"],
            "acceptance_criteria": ["string"],
            "risks": ["string"],
            "assumptions": ["string"]
          }
        }
        """
        .formatted(
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
            techStack,
            database,
            serverType,
            containerStrategy,
            !isNoneSelected(request.getTechStackChoice()),
            !isNoneSelected(request.getDatabaseChoice()),
            !isNoneSelected(request.getServerType()),
            !isNoneSelected(request.getContainerStrategy()));
  }

  @Override
  public String hldPrompt(DesignRequestDTO request) {
    String techStack = isNoneSelected(request.getTechStackChoice()) ? "Not used" : request.getTechStackChoice();
    String database = isNoneSelected(request.getDatabaseChoice()) ? "Not used" : request.getDatabaseChoice();
    String serverType = isNoneSelected(request.getServerType()) ? "Not used" : request.getServerType();
    String containerStrategy = isNoneSelected(request.getContainerStrategy()) ? "Not used"
        : request.getContainerStrategy();

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
        Stack Enabled: %s
        Database Enabled: %s
        Server Enabled: %s
        Container Strategy Enabled: %s

        Depth requirements:
        1) Cover how EACH functional requirement is handled by specific components.
        2) Include reliability, latency, availability, consistency, and security assumptions.
        3) Use realistic components such as: API Gateway, Load Balancer, Auth Service, User Service,
           Feed Service, Media Service, Notification Service, Search Service, Rate Limiter, Cache,
           Message Queue/Event Bus, Worker, Object Storage, CDN, SQL/NoSQL, Observability stack.
        4) Keep architecture text practical and implementation-oriented, not generic.
        5) Include at least 20 API contracts spanning auth, user/profile, core business objects,
           search/feed, notification, admin/ops, health/metrics, and integration endpoints.
        6) For every major module include explicit production notes:
           - ownership boundary
           - request path + async/event path
           - persistence strategy
           - failure handling and fallback behavior
           - observability signals (logs/metrics/traces)
        7) Include dedicated design viewpoints inspired by backend, software architect,
           devops, and docker roadmaps.
        8) Tailor the architecture and implementation choices strongly for the selected
           target platform, design domain, and preferred stack.
        9) If design domain is MOBILE, include BFF/API optimization, offline sync, push notifications,
           and mobile telemetry.
        10) If mobile use-case includes camera/photo/video capture, explicitly include a Camera Module
           with: device capability detection, permissions, focus/exposure/ISO/shutter controls,
           capture pipeline, processing/compression, metadata, and upload handoff.
        11) If design domain is FRONTEND, include rendering strategy, state management, caching strategy,
           asset delivery, and observability.
        12) If design domain is DEVOPS or SERVER_ARCHITECTURE, include deployment topology, CI/CD,
           infra automation, runtime operations, security controls, and SLO governance.
        13) For backend/server-architecture/devops designs, produce an explicit cloud topology narrative with:
            - external systems
            - scheduler/cron and ingestion pipeline
            - transform/ETL layer
            - edge/network/security (DNS, WAF, certificates, load balancer, subnets, NAT)
            - compute/runtime cluster
            - data layer (relational + document + cache)
            - monitoring/trace/logging
            - invite-only access workflow (invite, OTP, token, permission checks)
            - backup/audit storage and secrets management.
        14) If any capability is marked as "Not used", exclude related components, APIs, and strategy sections.
            Example: if database is not used, avoid database_schema entries and DB-specific components.
        15) Apply exclusion matrix strictly:
            - Preferred Stack = Not used => do not include backend business services, backend-only API contracts, or service-runtime internals.
            - Preferred Database = Not used => do not include database_schemas entries or DB nodes/edges.
            - Server Type = Not used => avoid server topology, host sizing, and server provisioning details.
            - Container Strategy = Not used => avoid Docker/Kubernetes/container orchestration strategy details.

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
        """
        .formatted(
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
            techStack,
            database,
            serverType,
            containerStrategy,
            !isNoneSelected(request.getTechStackChoice()),
            !isNoneSelected(request.getDatabaseChoice()),
            !isNoneSelected(request.getServerType()),
            !isNoneSelected(request.getContainerStrategy()));
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
        6) Return at least 12 components for production-level systems unless intentionally constrained.
        7) Each component responsibility must include: business purpose, key inputs/outputs,
           state management or persistence touchpoint, and major failure mode.
        8) If the product has mobile context, include mobile-specific modules:
           auth/onboarding, profile/session, feed/listing, local storage/offline sync,
           push notification integration, analytics/telemetry, and crash reporting.
        9) If mobile requirements mention camera/photo/video capture, include a dedicated Camera Module
           with explicit sub-capabilities in responsibility/implementation_approach:
           permissions, device capabilities, autofocus/manual focus, ISO, shutter speed,
           flash modes, image processing, and upload queue handoff.

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
        4) Each component entry should be implementation-ready:
           - module_description should cover control flow, storage/cache interaction, failure handling, and observability.
           - classes should include concrete class names plus role hints.
           - interfaces should include contract-level interfaces with clear names.
           - sequence should include at least 8 ordered steps for non-trivial modules.
        5) If there is a camera/capture module, include concrete internals such as:
           CameraPermissionManager, DeviceCapabilityService, ExposureController,
           FocusController, FlashController, CaptureSessionManager, ImageProcessingPipeline,
           CaptureMetadataStore, UploadOrchestrator.

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
        """
        .formatted(componentBreakdownJson);
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
        8) For server architecture and devops-heavy systems, include detailed infrastructure nodes similar to
           production cloud diagrams: DNS, certificate manager, WAF, load balancer, scheduler, ETL/transform,
           runtime cluster/control plane, private workers/nodes, NAT/outbound, secrets manager, audit/backup storage.
        9) Include both business/data flow and control/security flow edges with explicit labels.
        10) Produce at least 24 nodes and at least 30 edges for infrastructure-centric designs.
        11) Also return an "eraser_definition" string containing Eraser diagram DSL that matches the same architecture.

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
          "mermaid": "flowchart LR\\nclient[Client] --> gateway[API Gateway]",
          "eraser_definition": "title Example\\ndirection right\\n..."
        }
        """
        .formatted(hldJson, lldJson);
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
        6) Each module must have at least 10 task rows and each task row should include
           task-level hours for all 3 developer levels.
        7) If camera/capture module exists, include dedicated task rows for:
           permissions flow, camera session setup, autofocus/manual focus,
           ISO/shutter/flash controls, image processing/compression, upload retry queue,
           telemetry instrumentation, device compatibility tests, and battery/perf optimization.

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
    // Legacy all-in-one prompt retained as fallback compatibility.
    return """
        Generate implementation-grade product wireframes from design context.
        HLD_JSON:
        %s
        COMPONENT_BREAKDOWN_JSON:
        %s
        LLD_JSON:
        %s

        Return ONLY valid JSON. No markdown. No comments. No additional text.
        Follow this exact schema:
        {
          "wireframe_summary": "string",
          "screens": [
            {
              "screen_name": "string",
              "route_id": "string",
              "platform": "string",
              "purpose": "string",
              "layout_description": "string",
              "ui_components": ["string"],
              "interactions": ["string"],
              "api_bindings": ["string"],
              "next_screen_ids": ["string"],
              "screen_html": "string"
            }
          ]
        }
        """.formatted(hldJson, componentBreakdownJson, lldJson);
  }

  @Override
  public String wireframeScreenListPrompt(
      String hldJson, String componentBreakdownJson, String lldJson, String requirementContextJson) {
    return """
        You are a principal product designer and frontend architect.
        Generate a complete screen inventory for a clickable prototype from the system design context.
        HLD_JSON:
        %s
        COMPONENT_BREAKDOWN_JSON:
        %s
        LLD_JSON:
        %s
        REQUIREMENT_CONTEXT_JSON:
        %s

        Requirements:
        1) Return screen list only, no HTML.
        2) Include user-journey order from onboarding to core workflows to admin/ops.
        3) Include route connections so screens can be navigated as a prototype.
        4) Ensure web/mobile context is reflected in platform.
        5) For medium/large systems return at least 8 screens.
        6) Every functional requirement must be covered by at least one screen.
        7) Each screen must include:
           - screen_name
           - route_id (kebab-case)
           - platform
           - purpose
           - layout_description
           - ui_components (>= 8 items)
           - interactions (>= 4 items)
           - api_bindings (>= 3 items where APIs exist)
           - requirement_coverage (explicit list of functional requirements this screen addresses)
           - next_screen_ids
        8) If camera/capture use-case exists, include camera capture and media review screens with controls.
        9) Do not use placeholder wording. Keep content implementation-grade.

        Return ONLY valid JSON. No markdown. No comments. No extra text.
        Follow this exact schema:
        {
          "wireframe_summary": "string",
          "screens": [
            {
              "screen_name": "string",
              "route_id": "string",
              "platform": "string",
              "purpose": "string",
              "layout_description": "string",
              "ui_components": ["string"],
              "interactions": ["string"],
              "api_bindings": ["string"],
              "requirement_coverage": ["string"],
              "next_screen_ids": ["string"]
            }
          ]
        }
        """.formatted(hldJson, componentBreakdownJson, lldJson, requirementContextJson);
  }

  @Override
  public String wireframeScreenHtmlPrompt(
      String hldJson,
      String componentBreakdownJson,
      String lldJson,
      String requirementContextJson,
      String screenListJson,
      String screenSpecJson) {
    return """
        You are generating one screen for a high-fidelity clickable prototype.
        Use the system-design context and screen list to produce production-quality HTML for ONE screen.
        HLD_JSON:
        %s
        COMPONENT_BREAKDOWN_JSON:
        %s
        LLD_JSON:
        %s
        REQUIREMENT_CONTEXT_JSON:
        %s
        SCREEN_LIST_JSON:
        %s
        CURRENT_SCREEN_SPEC_JSON:
        %s

        Requirements:
        1) Return exactly one screen object.
        2) Preserve route_id and next_screen_ids so navigation remains connected.
        3) Produce pixel-perfect, high-fidelity, Google Stitch-level UI in screen_html.
        4) Use professional design tokens (rich shadows, modern typography, grid alignment).
        5) The HTML structure must be clean and semantic to be "Figma-ready" (compatible with html-to-figma conversion).
        6) Navigation actions must use data-nav-screen="<route-id>" and match next_screen_ids.
        7) Preserve or improve requirement_coverage and keep it tied to requirements in context.
        8) Do not use markdown, fenced code blocks, or placeholders.
        9) Keep all fields populated and implementation-ready.

        Return ONLY valid JSON. No markdown. No comments. No extra text.
        Follow this exact schema:
        {
          "screen_name": "string",
          "route_id": "string",
          "platform": "string",
          "purpose": "string",
          "layout_description": "string",
          "ui_components": ["string"],
          "interactions": ["string"],
          "api_bindings": ["string"],
          "requirement_coverage": ["string"],
          "next_screen_ids": ["string"],
          "screen_html": "string"
        }
        """
        .formatted(
            hldJson,
            componentBreakdownJson,
            lldJson,
            requirementContextJson,
            screenListJson,
            screenSpecJson);
  }

  @Override
  public String wireframeScreenRepairPrompt(
      String requirementContextJson,
      String screenSpecJson,
      String currentScreenJson,
      String validationError) {
    return """
        Repair the following screen JSON so that screen_html is valid and navigable.
        REQUIREMENT_CONTEXT_JSON:
        %s
        SCREEN_SPEC_JSON:
        %s
        CURRENT_SCREEN_JSON:
        %s
        VALIDATION_ERROR:
        %s

        Requirements:
        1) Keep route_id and next_screen_ids compatible with screen spec.
        2) Fix HTML so it is complete, semantic, and interactive.
        3) Include data-nav-screen values for every next_screen_id.
        4) Keep requirement_coverage aligned to functional requirements.
        5) Keep content high-fidelity and realistic for this screen purpose.
        6) Return strictly one JSON object with all required fields.

        Return ONLY valid JSON. No markdown. No comments. No extra text.
        Follow this exact schema:
        {
          "screen_name": "string",
          "route_id": "string",
          "platform": "string",
          "purpose": "string",
          "layout_description": "string",
          "ui_components": ["string"],
          "interactions": ["string"],
          "api_bindings": ["string"],
          "requirement_coverage": ["string"],
          "next_screen_ids": ["string"],
          "screen_html": "string"
        }
        """.formatted(requirementContextJson, screenSpecJson, currentScreenJson, validationError);
  }

  @Override
  public String wireframeIterationPrompt(
      String hldJson,
      String componentBreakdownJson,
      String lldJson,
      String currentWireframeJson,
      String userPrompt) {
    return """
        You are a senior product designer and UI architect.
        Iterate on the existing product wireframes based on the user's refinement prompt.

        SOURCE CONTEXT (System Design):
        HLD: %s
        COMPONENTS: %s
        LLD: %s

        CURRENT WIREFRAMES:
        %s

        USER REFINEMENT PROMPT:
        "%s"

        Requirements:
        1) Apply the user's requested changes to the wireframe inventory.
        2) You can add new screens, modify existing screen layouts/HTML, or change the flow (next_screen_ids).
        3) When modifying HTML, ensure it remains pixel-perfect, Google-Stitch level fidelity, and Figma-ready.
        4) Focus on modern component design: clean cards, meaningful density, refined spacing, and professional color harmony.
        5) If the user asks for a specific screen or modification, focus on producing high-quality HTML for it.
        6) If the user asks for global changes (e.g., "use a dark theme" or "make all buttons rounded"), apply it to all screens consistently.
        7) Return the ENTIRE updated wireframe inventory (wireframe_summary + all screens).
        8) Keep route_id and next_screen_ids consistent for proper prototype navigation.
        9) Keep requirement_coverage on every screen and ensure all listed requirements are covered.
        10) Do not use markdown, fenced code blocks, or placeholders.

        Return ONLY valid JSON. No markdown. No comments. No extra text.
        Follow this exact schema:
        {
          "wireframe_summary": "string",
          "screens": [
            {
              "screen_name": "string",
              "route_id": "string",
              "platform": "string",
              "purpose": "string",
              "layout_description": "string",
              "ui_components": ["string"],
              "interactions": ["string"],
              "api_bindings": ["string"],
              "requirement_coverage": ["string"],
              "next_screen_ids": ["string"],
              "screen_html": "string"
            }
          ]
        }
        """
        .formatted(hldJson, componentBreakdownJson, lldJson, currentWireframeJson, userPrompt);
  }

  @Override
  public String invalidJsonRetrySuffix() {
    return """

        Your previous response was invalid JSON.
        Return only valid JSON that matches the schema exactly.
        """;
  }
}
