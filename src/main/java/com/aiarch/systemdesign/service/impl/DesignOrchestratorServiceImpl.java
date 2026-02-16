package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignStageResult;
import com.aiarch.systemdesign.dto.document.ApiContract;
import com.aiarch.systemdesign.dto.document.Component;
import com.aiarch.systemdesign.dto.document.ComponentLLD;
import com.aiarch.systemdesign.dto.document.DataFlowScenario;
import com.aiarch.systemdesign.dto.document.DatabaseSchema;
import com.aiarch.systemdesign.dto.document.DiagramEdge;
import com.aiarch.systemdesign.dto.document.DiagramMetadata;
import com.aiarch.systemdesign.dto.document.DiagramNode;
import com.aiarch.systemdesign.dto.document.DiagramNodeData;
import com.aiarch.systemdesign.dto.document.DiagramPosition;
import com.aiarch.systemdesign.dto.document.SystemDesignDocument;
import com.aiarch.systemdesign.dto.document.TaskBreakdownItem;
import com.aiarch.systemdesign.dto.document.TaskBreakdownTask;
import com.aiarch.systemdesign.mapper.SystemDesignDocumentMapper;
import com.aiarch.systemdesign.model.SystemDesign;
import com.aiarch.systemdesign.repository.SystemDesignRepository;
import com.aiarch.systemdesign.service.AIStageService;
import com.aiarch.systemdesign.service.DesignGenerationPublisher;
import com.aiarch.systemdesign.service.DesignOrchestratorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DesignOrchestratorServiceImpl implements DesignOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(DesignOrchestratorServiceImpl.class);
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<Component>> COMPONENT_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<ComponentLLD>> COMPONENT_LLD_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<ApiContract>> API_CONTRACT_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<DatabaseSchema>> DATABASE_SCHEMA_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<DataFlowScenario>> DATA_FLOW_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<TaskBreakdownItem>> TASK_BREAKDOWN_TYPE = new TypeReference<>() { };
    private static final int MIN_API_CONTRACTS = 20;
    private static final int MIN_VISUAL_NODES = 10;
    private static final int BASE_X_SPACING = 280;
    private static final int BASE_Y_SPACING = 150;

    private final AIStageService aiStageService;
    private final SystemDesignRepository systemDesignRepository;
    private final ObjectMapper objectMapper;
    private final SystemDesignDocumentMapper documentMapper;
    private final DesignGenerationPublisher designGenerationPublisher;

    @Qualifier("orchestratorTaskExecutor")
    private final Executor orchestratorTaskExecutor;

    @Override
    @Async("designGenerationTaskExecutor")
    @Transactional
    public CompletableFuture<Void> generateDesignAsync(UUID designId, DesignRequestDTO request) {
        log.info(
                "Starting async design orchestration for designId={} product={}",
                designId,
                request.getProductName()
        );

        try {
            DesignStageResult hld = executeStage(designId, "HLD", 10, () -> aiStageService.generateHLD(request));
            DesignStageResult componentBreakdown = executeStage(
                    designId,
                    "COMPONENT_BREAKDOWN",
                    25,
                    () -> aiStageService.generateComponentBreakdown(hld)
            );
            List<Component> componentsForFallback = extractComponentsForFallback(componentBreakdown);
            DesignStageResult lld = executeStage(
                    designId,
                    "LLD",
                    50,
                    () -> aiStageService.generateLLD(componentBreakdown)
            );

            CompletableFuture<DesignStageResult> scalingFuture = CompletableFuture.supplyAsync(
                    () -> executeStage(
                            designId,
                            "SCALING_STRATEGY",
                            80,
                            () -> aiStageService.generateScalingStrategy(hld)
                    ),
                    orchestratorTaskExecutor
            );
            CompletableFuture<DesignStageResult> failureFuture = CompletableFuture.supplyAsync(
                    () -> executeStage(
                            designId,
                            "FAILURE_HANDLING",
                            90,
                            () -> aiStageService.generateFailureHandling(hld)
                    ),
                    orchestratorTaskExecutor
            );

            DesignStageResult dataFlow = executeStage(
                    designId,
                    "DATA_FLOW",
                    65,
                    () -> aiStageService.generateDataFlow(hld, lld)
            );
            DesignStageResult diagramMetadata = executeDiagramStageWithFallback(
                    designId,
                    hld,
                    lld,
                    componentsForFallback
            );
            DesignStageResult taskBreakdown = executeTaskBreakdownStageWithFallback(
                    designId,
                    hld,
                    componentBreakdown,
                    lld,
                    componentsForFallback
            );
            DesignStageResult scaling = scalingFuture.join();
            DesignStageResult failureHandling = failureFuture.join();

            SystemDesignDocument document = buildDocument(
                    hld.getContent(),
                    componentBreakdown.getContent(),
                    lld.getContent(),
                    dataFlow.getContent(),
                    scaling.getContent(),
                    failureHandling.getContent(),
                    diagramMetadata.getContent(),
                    taskBreakdown.getContent()
            );

            int nextVersion = systemDesignRepository.findTopByProductNameOrderByVersionDesc(request.getProductName())
                    .map(existing -> existing.getVersion() + 1)
                    .orElse(1);

            SystemDesign systemDesign = SystemDesign.builder()
                    .id(designId)
                    .productName(request.getProductName())
                    .version(nextVersion)
                    .documentJson(documentMapper.toJsonNode(document))
                    .build();
            systemDesignRepository.save(systemDesign);
            designGenerationPublisher.publishStageCompleted(
                    designId.toString(),
                    "ORCHESTRATION",
                    100,
                    documentMapper.toJsonNode(document).toString()
            );

            log.info(
                    "Completed orchestration for designId={} product={} version={}",
                    designId,
                    request.getProductName(),
                    nextVersion
            );
            return CompletableFuture.completedFuture(null);
        } catch (Exception ex) {
            log.error("Design orchestration failed for designId={}", designId, ex);
            designGenerationPublisher.publishStageFailed(
                    designId.toString(),
                    "ORCHESTRATION",
                    ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "Unknown error" : ex.getMessage())
            );
            return CompletableFuture.failedFuture(ex);
        }
    }

    private SystemDesignDocument buildDocument(
            String hldJson,
            String componentJson,
            String lldJson,
            String dataFlowJson,
            String scalingJson,
            String failureJson,
            String diagramJson,
            String taskBreakdownJson
    ) {
        JsonNode hldNode = readJson(hldJson);
        JsonNode componentNode = readJson(componentJson);
        JsonNode lldNode = readJson(lldJson);
        JsonNode dataFlowNode = readJson(dataFlowJson);
        JsonNode scalingNode = readJson(scalingJson);
        JsonNode failureNode = readJson(failureJson);
        JsonNode diagramNode = readJson(diagramJson);
        JsonNode taskBreakdownNode = readJson(taskBreakdownJson);

        List<Component> components = enrichComponents(readList(componentNode, "components", COMPONENT_LIST_TYPE));
        List<ApiContract> apiContracts = readList(hldNode, "api_contracts", API_CONTRACT_LIST_TYPE);
        List<ApiContract> enrichedApiContracts = ensureRichApiContracts(apiContracts, components);
        DiagramMetadata diagramMetadata = enrichDiagramMetadata(readObject(diagramNode, DiagramMetadata.class), components);
        List<TaskBreakdownItem> taskBreakdown = normalizeTaskBreakdown(readList(taskBreakdownNode, "task_breakdown", TASK_BREAKDOWN_TYPE));
        if (taskBreakdown.isEmpty()) {
            taskBreakdown = buildFallbackTaskBreakdown(components);
        }

        return SystemDesignDocument.builder()
                .overview(getText(hldNode, "overview"))
                .assumptions(readList(hldNode, "assumptions", STRING_LIST_TYPE))
                .capacityEstimation(getText(hldNode, "capacity_estimation"))
                .hld(resolveHldText(hldNode, componentNode))
                .components(components)
                .lld(readList(lldNode, "lld", COMPONENT_LLD_LIST_TYPE))
                .apiContracts(enrichedApiContracts)
                .databaseSchemas(readList(hldNode, "database_schemas", DATABASE_SCHEMA_LIST_TYPE))
                .dataFlowScenarios(readList(dataFlowNode, "data_flow_scenarios", DATA_FLOW_LIST_TYPE))
                .scalingStrategy(resolveScalingText(scalingNode))
                .failureHandling(resolveFailureText(failureNode))
                .tradeoffs(getText(hldNode, "tradeoffs"))
                .taskBreakdown(taskBreakdown)
                .diagramMetadata(diagramMetadata)
                .build();
    }

    private JsonNode readJson(String rawJson) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse generated stage JSON", ex);
        }
    }

    private String getText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private <T> List<T> readList(JsonNode node, String field, TypeReference<List<T>> typeReference) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(value, typeReference);
        } catch (IllegalArgumentException ex) {
            log.warn("Failed to map section={} to target list. Falling back to empty list.", field, ex);
            return List.of();
        }
    }

    private <T> T readObject(JsonNode node, Class<T> clazz) {
        try {
            return objectMapper.convertValue(node, clazz);
        } catch (IllegalArgumentException ex) {
            log.warn("Failed to map section object to {}. Falling back to null.", clazz.getSimpleName(), ex);
            return null;
        }
    }

    private String resolveHldText(JsonNode hldNode, JsonNode componentNode) {
        String hldText = getText(hldNode, "hld");
        String backendArchitecture = getText(hldNode, "backend_architecture");
        String softwareArchitecture = getText(hldNode, "software_architecture");
        String devopsStrategy = getText(hldNode, "devops_strategy");
        String dockerStrategy = getText(hldNode, "docker_strategy");

        StringBuilder combined = new StringBuilder();
        if (!hldText.isBlank()) {
            combined.append(hldText.trim());
        }
        appendSection(combined, "Backend Architecture", backendArchitecture);
        appendSection(combined, "Software Architecture", softwareArchitecture);
        appendSection(combined, "DevOps Strategy", devopsStrategy);
        appendSection(combined, "Docker Strategy", dockerStrategy);

        String combinedText = combined.toString().trim();
        if (!combinedText.isBlank()) {
            return combinedText;
        }

        List<Component> components = readList(componentNode, "components", COMPONENT_LIST_TYPE);
        if (components.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Component-level architecture:\n");
        for (Component component : components) {
            builder.append("- ")
                    .append(component.getName() == null ? "component" : component.getName())
                    .append(" [")
                    .append(component.getType() == null ? "type-unknown" : component.getType())
                    .append("] -> ")
                    .append(component.getResponsibility() == null ? "responsibility not provided" : component.getResponsibility())
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String resolveScalingText(JsonNode scalingNode) {
        String scalingText = getText(scalingNode, "scaling_strategy");
        if (!scalingText.isBlank()) {
            return scalingText;
        }
        return "Scaling strategy will cover horizontal scaling, partitioning, caching, and async backpressure controls.";
    }

    private String resolveFailureText(JsonNode failureNode) {
        String failureText = getText(failureNode, "failure_handling");
        if (!failureText.isBlank()) {
            return failureText;
        }
        return "Failure handling strategy includes retries, circuit breakers, graceful degradation, and operational recovery steps.";
    }

    private List<Component> enrichComponents(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return List.of();
        }
        List<Component> sanitized = new ArrayList<>();
        int nextOrder = 1;
        for (Component component : components) {
            if (component == null) {
                continue;
            }
            if (component.getBuildOrder() == null || component.getBuildOrder() <= 0) {
                component.setBuildOrder(nextOrder);
            }
            if (component.getImplementationApproach() == null || component.getImplementationApproach().isBlank()) {
                component.setImplementationApproach("Implement with clear interfaces, unit tests, and production-grade observability.");
            }
            nextOrder = Math.max(nextOrder + 1, component.getBuildOrder() + 1);
            sanitized.add(component);
        }
        sanitized.sort(Comparator.comparing(Component::getBuildOrder));
        return sanitized;
    }

    private List<ApiContract> ensureRichApiContracts(List<ApiContract> original, List<Component> components) {
        Map<String, ApiContract> byMethodPath = new LinkedHashMap<>();
        if (original != null) {
            for (ApiContract api : original) {
                if (api == null) {
                    continue;
                }
                String method = normalizeMethod(api.getMethod());
                String path = normalizePath(api.getPath());
                if (path.isBlank()) {
                    continue;
                }
                api.setMethod(method.isBlank() ? "GET" : method);
                api.setPath(path);
                byMethodPath.put(api.getMethod() + " " + api.getPath(), api);
            }
        }

        for (ApiContract fallback : buildFallbackApis(components)) {
            byMethodPath.putIfAbsent(fallback.getMethod() + " " + fallback.getPath(), fallback);
            if (byMethodPath.size() >= MIN_API_CONTRACTS) {
                break;
            }
        }

        List<ApiContract> finalList = new ArrayList<>(byMethodPath.values());
        if (finalList.size() < MIN_API_CONTRACTS) {
            log.warn("API contracts still below target size={} actual={}", MIN_API_CONTRACTS, finalList.size());
        }
        return finalList;
    }

    private List<ApiContract> buildFallbackApis(List<Component> components) {
        List<ApiContract> apis = new ArrayList<>();
        apis.add(api("Register User", "POST", "/api/v1/auth/register"));
        apis.add(api("Login", "POST", "/api/v1/auth/login"));
        apis.add(api("Logout", "POST", "/api/v1/auth/logout"));
        apis.add(api("Refresh Token", "POST", "/api/v1/auth/refresh"));
        apis.add(api("Get User Profile", "GET", "/api/v1/users/{userId}"));
        apis.add(api("Update User Profile", "PUT", "/api/v1/users/{userId}"));
        apis.add(api("Update User Preferences", "PATCH", "/api/v1/users/{userId}/preferences"));
        apis.add(api("Get User Session", "GET", "/api/v1/sessions/current"));
        apis.add(api("Create Core Resource", "POST", "/api/v1/resources"));
        apis.add(api("Read Core Resource", "GET", "/api/v1/resources/{resourceId}"));
        apis.add(api("Update Core Resource", "PUT", "/api/v1/resources/{resourceId}"));
        apis.add(api("Delete Core Resource", "DELETE", "/api/v1/resources/{resourceId}"));
        apis.add(api("List Feed", "GET", "/api/v1/feed"));
        apis.add(api("List Personalized Feed", "GET", "/api/v1/feed/personalized"));
        apis.add(api("Search", "GET", "/api/v1/search"));
        apis.add(api("Get Recommendations", "GET", "/api/v1/recommendations"));
        apis.add(api("Register Notification Token", "POST", "/api/v1/notifications/token"));
        apis.add(api("List Notifications", "GET", "/api/v1/notifications"));
        apis.add(api("Mark Notification Read", "PATCH", "/api/v1/notifications/{notificationId}/read"));
        apis.add(api("Sync Mobile Offline Queue", "POST", "/api/v1/mobile/sync"));
        apis.add(api("Upload Media", "POST", "/api/v1/media/upload"));
        apis.add(api("Fetch Feature Flags", "GET", "/api/v1/config/feature-flags"));
        apis.add(api("Health Check", "GET", "/api/v1/health"));
        apis.add(api("Metrics", "GET", "/api/v1/metrics"));
        apis.add(api("Readiness", "GET", "/api/v1/readiness"));
        apis.add(api("Liveness", "GET", "/api/v1/liveness"));
        apis.add(api("Create Admin Job", "POST", "/api/v1/admin/jobs"));
        apis.add(api("List Admin Jobs", "GET", "/api/v1/admin/jobs"));
        apis.add(api("Get Audit Logs", "GET", "/api/v1/admin/audit-logs"));

        if (components != null) {
            for (Component component : components) {
                if (component == null || component.getName() == null || component.getName().isBlank()) {
                    continue;
                }
                String slug = component.getName()
                        .toLowerCase()
                        .replace("service", "")
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-|-$", "");
                if (slug.isBlank()) {
                    continue;
                }
                apis.add(api("List " + component.getName(), "GET", "/api/v1/" + slug));
                apis.add(api("Create " + component.getName(), "POST", "/api/v1/" + slug));
            }
        }
        return apis;
    }

    private List<TaskBreakdownItem> buildFallbackTaskBreakdown(List<Component> components) {
        List<Component> sourceComponents = components;
        if (sourceComponents == null || sourceComponents.isEmpty()) {
            sourceComponents = List.of(
                    Component.builder().name("Foundation Setup").type("service").build(),
                    Component.builder().name("Core Module Development").type("service").build(),
                    Component.builder().name("Data Layer & Integrations").type("database").build(),
                    Component.builder().name("Quality, Observability & Hardening").type("observability").build(),
                    Component.builder().name("Release & Deployment").type("devops").build()
            );
        }
        List<TaskBreakdownItem> items = new ArrayList<>();
        for (Component component : sourceComponents) {
            if (component == null || component.getName() == null || component.getName().isBlank()) {
                continue;
            }
            int base = estimateBaseHours(component);
            List<TaskBreakdownTask> tasks = defaultTasksForComponent(component, base);
            items.add(TaskBreakdownItem.builder()
                    .moduleName(component.getName())
                    .implementationApproach(component.getImplementationApproach())
                    .tasks(tasks)
                    .hoursExperiencedDeveloper(sumTaskHours(tasks, Role.EXPERIENCED))
                    .hoursMidLevelDeveloper(sumTaskHours(tasks, Role.MID))
                    .hoursFresher(sumTaskHours(tasks, Role.FRESHER))
                    .build());
        }
        return items;
    }

    private int estimateBaseHours(Component component) {
        int dependencyWeight = component.getDependencies() == null ? 0 : component.getDependencies().size() * 2;
        int complexityWeight = 6;
        String type = component.getType() == null ? "" : component.getType().toLowerCase();
        if (type.contains("database") || type.contains("queue") || type.contains("cache")) {
            complexityWeight = 10;
        } else if (type.contains("gateway") || type.contains("auth")) {
            complexityWeight = 12;
        } else if (type.contains("mobile") || type.contains("frontend")) {
            complexityWeight = 9;
        }
        return Math.max(8, complexityWeight + dependencyWeight);
    }

    private List<TaskBreakdownItem> normalizeTaskBreakdown(List<TaskBreakdownItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<TaskBreakdownItem> normalized = new ArrayList<>();
        for (TaskBreakdownItem item : items) {
            if (item == null || item.getModuleName() == null || item.getModuleName().isBlank()) {
                continue;
            }

            List<TaskBreakdownTask> tasks = item.getTasks();
            if (tasks == null || tasks.isEmpty()) {
                Component pseudo = Component.builder().name(item.getModuleName()).type("service").build();
                tasks = defaultTasksForComponent(pseudo, 10);
            } else {
                List<TaskBreakdownTask> sanitizedTasks = new ArrayList<>();
                for (TaskBreakdownTask task : tasks) {
                    if (task == null || task.getTaskName() == null || task.getTaskName().isBlank()) {
                        continue;
                    }
                    int experienced = positiveOr(task.getHoursExperiencedDeveloper(), 2);
                    int mid = positiveOr(task.getHoursMidLevelDeveloper(), (int) Math.round(experienced * 1.5));
                    int fresher = positiveOr(task.getHoursFresher(), experienced * 2);
                    sanitizedTasks.add(TaskBreakdownTask.builder()
                            .taskName(task.getTaskName())
                            .description(task.getDescription() == null || task.getDescription().isBlank()
                                    ? "Implement and verify task completion."
                                    : task.getDescription())
                            .hoursExperiencedDeveloper(experienced)
                            .hoursMidLevelDeveloper(mid)
                            .hoursFresher(fresher)
                            .build());
                }
                tasks = sanitizedTasks;
            }

            int experiencedTotal = sumTaskHours(tasks, Role.EXPERIENCED);
            int midTotal = sumTaskHours(tasks, Role.MID);
            int fresherTotal = sumTaskHours(tasks, Role.FRESHER);

            normalized.add(TaskBreakdownItem.builder()
                    .moduleName(item.getModuleName())
                    .implementationApproach(
                            item.getImplementationApproach() == null || item.getImplementationApproach().isBlank()
                                    ? "Build iteratively with contracts, tests, and deployment validation."
                                    : item.getImplementationApproach()
                    )
                    .tasks(tasks)
                    .hoursExperiencedDeveloper(positiveOr(item.getHoursExperiencedDeveloper(), experiencedTotal))
                    .hoursMidLevelDeveloper(positiveOr(item.getHoursMidLevelDeveloper(), midTotal))
                    .hoursFresher(positiveOr(item.getHoursFresher(), fresherTotal))
                    .build());
        }
        return normalized;
    }

    private List<TaskBreakdownTask> defaultTasksForComponent(Component component, int baseHours) {
        String type = component.getType() == null ? "" : component.getType().toLowerCase();
        List<TaskBreakdownTask> tasks = new ArrayList<>();
        tasks.add(task("Requirements & acceptance", "Finalize user stories, edge cases, and acceptance criteria.", 2));
        tasks.add(task("Module scaffolding", "Create project/module structure, interfaces, and configs.", 2));
        tasks.add(task("Core implementation", "Implement core business logic for the module.", Math.max(3, baseHours / 4)));
        tasks.add(task("Integration wiring", "Integrate dependencies and external/internal APIs.", Math.max(2, baseHours / 6)));
        tasks.add(task("Test coverage", "Add unit/integration tests and fix defects.", Math.max(2, baseHours / 6)));
        tasks.add(task("Observability & hardening", "Add logs/metrics/alerts, review reliability and security.", 2));

        if (type.contains("mobile") || type.contains("frontend")) {
            tasks.add(task("UI states & accessibility", "Build loading/error/empty states and accessibility support.", 3));
            tasks.add(task("Offline & sync behavior", "Implement local persistence and sync conflict handling.", 3));
        }
        if (type.contains("auth") || type.contains("gateway")) {
            tasks.add(task("Auth/security policies", "Implement token validation, RBAC, and abuse controls.", 3));
        }
        if (type.contains("database") || type.contains("cache") || type.contains("queue")) {
            tasks.add(task("Schema/capacity tuning", "Tune schema/index/retention and validate load behavior.", 3));
        }
        return tasks;
    }

    private TaskBreakdownTask task(String name, String description, int experiencedHours) {
        return TaskBreakdownTask.builder()
                .taskName(name)
                .description(description)
                .hoursExperiencedDeveloper(experiencedHours)
                .hoursMidLevelDeveloper((int) Math.round(experiencedHours * 1.5))
                .hoursFresher(experiencedHours * 2)
                .build();
    }

    private int sumTaskHours(List<TaskBreakdownTask> tasks, Role role) {
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (TaskBreakdownTask task : tasks) {
            if (task == null) {
                continue;
            }
            sum += switch (role) {
                case EXPERIENCED -> positiveOr(task.getHoursExperiencedDeveloper(), 0);
                case MID -> positiveOr(task.getHoursMidLevelDeveloper(), 0);
                case FRESHER -> positiveOr(task.getHoursFresher(), 0);
            };
        }
        return sum;
    }

    private int positiveOr(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    private enum Role {
        EXPERIENCED,
        MID,
        FRESHER
    }

    private ApiContract api(String name, String method, String path) {
        return ApiContract.builder()
                .name(name)
                .method(method)
                .path(path)
                .requestSchema("{}")
                .responseSchema("{}")
                .errorCodes(List.of("400", "401", "403", "404", "429", "500"))
                .build();
    }

    private String normalizeMethod(String method) {
        return method == null ? "" : method.trim().toUpperCase();
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.trim();
    }

    private List<Component> extractComponentsForFallback(DesignStageResult componentBreakdown) {
        if (componentBreakdown == null || componentBreakdown.getContent() == null || componentBreakdown.getContent().isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = readJson(componentBreakdown.getContent());
            return enrichComponents(readList(node, "components", COMPONENT_LIST_TYPE));
        } catch (Exception ex) {
            log.warn("Failed to extract components for fallback path", ex);
            return List.of();
        }
    }

    private DesignStageResult executeDiagramStageWithFallback(
            UUID designId,
            DesignStageResult hld,
            DesignStageResult lld,
            List<Component> components
    ) {
        final String stageName = "DIAGRAM_METADATA";
        final int progress = 96;
        designGenerationPublisher.publishStageStarted(designId.toString(), stageName, progress);
        log.info("DesignId={} stage={} started", designId, stageName);
        try {
            DesignStageResult result = aiStageService.generateDiagramMetadata(hld, lld);
            designGenerationPublisher.publishStageCompleted(designId.toString(), stageName, progress, result.getContent());
            log.info("DesignId={} stage={} completed", designId, stageName);
            return result;
        } catch (Exception ex) {
            log.warn("DesignId={} stage={} failed. Falling back to deterministic diagram.", designId, stageName, ex);
            designGenerationPublisher.publishStageFailed(designId.toString(), stageName, ex.getMessage());
            DiagramMetadata fallbackDiagram = enrichDiagramMetadata(null, components);
            String fallbackJson = toJsonSafely(fallbackDiagram, "{}");
            DesignStageResult fallback = DesignStageResult.builder()
                    .stageName(stageName)
                    .content(fallbackJson)
                    .createdAt(LocalDateTime.now())
                    .build();
            designGenerationPublisher.publishStageCompleted(designId.toString(), stageName, progress, fallbackJson);
            log.info("DesignId={} stage={} completed with fallback output", designId, stageName);
            return fallback;
        }
    }

    private DesignStageResult executeTaskBreakdownStageWithFallback(
            UUID designId,
            DesignStageResult hld,
            DesignStageResult componentBreakdown,
            DesignStageResult lld,
            List<Component> components
    ) {
        final String stageName = "TASK_BREAKDOWN";
        final int progress = 100;
        designGenerationPublisher.publishStageStarted(designId.toString(), stageName, progress);
        log.info("DesignId={} stage={} started", designId, stageName);
        try {
            DesignStageResult result = aiStageService.generateTaskBreakdown(hld, componentBreakdown, lld);
            designGenerationPublisher.publishStageCompleted(designId.toString(), stageName, progress, result.getContent());
            log.info("DesignId={} stage={} completed", designId, stageName);
            return result;
        } catch (Exception ex) {
            log.warn("DesignId={} stage={} failed. Falling back to deterministic task breakdown.", designId, stageName, ex);
            designGenerationPublisher.publishStageFailed(designId.toString(), stageName, ex.getMessage());
            List<TaskBreakdownItem> fallbackItems = buildFallbackTaskBreakdown(components);
            String fallbackJson = toJsonSafely(Map.of("task_breakdown", fallbackItems), "{\"task_breakdown\":[]}");
            DesignStageResult fallback = DesignStageResult.builder()
                    .stageName(stageName)
                    .content(fallbackJson)
                    .createdAt(LocalDateTime.now())
                    .build();
            designGenerationPublisher.publishStageCompleted(designId.toString(), stageName, progress, fallbackJson);
            log.info("DesignId={} stage={} completed with fallback output", designId, stageName);
            return fallback;
        }
    }

    private String toJsonSafely(Object value, String fallbackJson) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize fallback object to JSON", ex);
            return fallbackJson;
        }
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(title).append(":\n").append(content.trim());
    }

    private DiagramMetadata enrichDiagramMetadata(DiagramMetadata rawMetadata, List<Component> components) {
        Map<String, DiagramNode> nodesById = new LinkedHashMap<>();
        if (rawMetadata != null && rawMetadata.getNodes() != null) {
            for (DiagramNode rawNode : rawMetadata.getNodes()) {
                DiagramNode normalized = normalizeNode(rawNode);
                if (normalized != null) {
                    nodesById.put(normalized.getId(), normalized);
                }
            }
        }

        if (components != null) {
            for (Component component : components) {
                if (component == null || component.getName() == null || component.getName().isBlank()) {
                    continue;
                }
                String id = slugify(component.getName());
                String type = normalizeType(component.getType() == null ? component.getName() : component.getType());
                DiagramNode fallbackNode = createNode(
                        id,
                        type,
                        component.getName(),
                        defaultTechnology(type),
                        component.getResponsibility(),
                        normalizeLayer(type)
                );
                nodesById.putIfAbsent(id, fallbackNode);
            }
        }

        ensureDefaultNode(nodesById, "client", "client", "Client Apps", "Web / Mobile", "End users and client applications");
        ensureDefaultNode(nodesById, "cdn", "cdn", "CDN", "CloudFront / Cloud CDN", "Caches static and edge content");
        ensureDefaultNode(nodesById, "api-gateway", "gateway", "API Gateway", "Kong / Nginx", "Single entry and routing");
        ensureDefaultNode(nodesById, "auth-service", "service", "Auth Service", "OAuth2 / JWT", "Authentication and authorization");
        ensureDefaultNode(nodesById, "core-service", "service", "Core Service", "Spring Boot", "Core business logic and orchestration");
        ensureDefaultNode(nodesById, "cache", "cache", "Cache", "Redis", "Low-latency hot data");
        ensureDefaultNode(nodesById, "message-queue", "queue", "Message Queue", "Kafka / SQS", "Asynchronous decoupling");
        ensureDefaultNode(nodesById, "worker", "worker", "Async Worker", "Spring Worker", "Background processing and retries");
        ensureDefaultNode(nodesById, "primary-database", "database", "Primary Database", "PostgreSQL", "System of record");
        ensureDefaultNode(nodesById, "observability", "observability", "Observability", "Prometheus / Grafana", "Metrics, logs and traces");

        List<DiagramNode> nodes = new ArrayList<>(nodesById.values());
        if (nodes.size() < MIN_VISUAL_NODES) {
            ensureDefaultNode(nodesById, "object-storage", "storage", "Object Storage", "S3 / GCS", "Stores large binary artifacts");
            nodes = new ArrayList<>(nodesById.values());
        }

        List<DiagramEdge> edges = normalizeEdges(
                rawMetadata == null ? null : rawMetadata.getEdges(),
                nodesById.keySet(),
                components
        );
        assignPositions(nodes);

        String mermaid = rawMetadata == null ? "" : rawMetadata.getMermaid();
        if (mermaid == null || mermaid.isBlank()) {
            mermaid = buildMermaid(nodes, edges);
        }

        return DiagramMetadata.builder()
                .nodes(nodes)
                .edges(edges)
                .mermaid(mermaid)
                .build();
    }

    private DiagramNode normalizeNode(DiagramNode rawNode) {
        if (rawNode == null) {
            return null;
        }
        String candidateId = rawNode.getId();
        if ((candidateId == null || candidateId.isBlank()) && rawNode.getData() != null) {
            candidateId = rawNode.getData().getLabel();
        }
        String id = slugify(candidateId);
        if (id.isBlank()) {
            return null;
        }

        String type = normalizeType(rawNode.getType());
        if (rawNode.getData() != null && (type.isBlank() || "service".equals(type))) {
            type = normalizeType(rawNode.getData().getLayer() == null ? rawNode.getType() : rawNode.getData().getLayer());
        }
        String finalType = type.isBlank() ? "service" : type;

        DiagramNodeData current = rawNode.getData() == null ? new DiagramNodeData() : rawNode.getData();
        DiagramNodeData data = DiagramNodeData.builder()
                .label(current.getLabel() == null || current.getLabel().isBlank() ? titleFromId(id) : current.getLabel())
                .technology(current.getTechnology() == null || current.getTechnology().isBlank()
                        ? defaultTechnology(finalType)
                        : current.getTechnology())
                .description(current.getDescription() == null || current.getDescription().isBlank()
                        ? "Auto-generated architecture node"
                        : current.getDescription())
                .layer(current.getLayer() == null || current.getLayer().isBlank()
                        ? normalizeLayer(finalType)
                        : current.getLayer().toUpperCase())
                .build();

        return DiagramNode.builder()
                .id(id)
                .type(finalType)
                .position(rawNode.getPosition())
                .data(data)
                .build();
    }

    private void ensureDefaultNode(
            Map<String, DiagramNode> nodesById,
            String id,
            String type,
            String label,
            String technology,
            String description
    ) {
        nodesById.computeIfAbsent(
                id,
                key -> createNode(key, type, label, technology, description, normalizeLayer(type))
        );
    }

    private DiagramNode createNode(
            String id,
            String type,
            String label,
            String technology,
            String description,
            String layer
    ) {
        return DiagramNode.builder()
                .id(slugify(id))
                .type(normalizeType(type))
                .data(DiagramNodeData.builder()
                        .label(label)
                        .technology(technology)
                        .description(description)
                        .layer(layer)
                        .build())
                .build();
    }

    private List<DiagramEdge> normalizeEdges(
            List<DiagramEdge> rawEdges,
            Set<String> validNodeIds,
            List<Component> components
    ) {
        Map<String, DiagramEdge> deduped = new LinkedHashMap<>();
        if (rawEdges != null) {
            for (DiagramEdge rawEdge : rawEdges) {
                DiagramEdge normalized = sanitizeEdge(rawEdge, validNodeIds);
                if (normalized != null) {
                    deduped.put(edgeKey(normalized), normalized);
                }
            }
        }

        String client = findNodeId(validNodeIds, "client");
        String cdn = findNodeId(validNodeIds, "cdn");
        String gateway = findNodeId(validNodeIds, "gateway", "api-gateway");
        String auth = findNodeId(validNodeIds, "auth");
        String core = findNodeId(validNodeIds, "core-service", "service");
        String cache = findNodeId(validNodeIds, "cache");
        String queue = findNodeId(validNodeIds, "queue", "message-queue");
        String worker = findNodeId(validNodeIds, "worker");
        String database = findNodeId(validNodeIds, "database", "primary-database");
        String observability = findNodeId(validNodeIds, "observability");

        addEdgeIfAbsent(deduped, client, cdn, "HTTPS");
        addEdgeIfAbsent(deduped, cdn, gateway, "HTTPS");
        addEdgeIfAbsent(deduped, gateway, auth, "OAuth2/JWT");
        addEdgeIfAbsent(deduped, gateway, core, "REST/gRPC");
        addEdgeIfAbsent(deduped, core, cache, "Redis");
        addEdgeIfAbsent(deduped, core, queue, "Kafka/SQS");
        addEdgeIfAbsent(deduped, queue, worker, "Async Event");
        addEdgeIfAbsent(deduped, core, database, "SQL");
        addEdgeIfAbsent(deduped, worker, database, "SQL");
        addEdgeIfAbsent(deduped, core, observability, "Metrics/Trace");
        addEdgeIfAbsent(deduped, worker, observability, "Metrics/Trace");

        if (components != null && gateway != null) {
            for (Component component : components) {
                if (component == null || component.getName() == null || component.getName().isBlank()) {
                    continue;
                }
                String componentId = slugify(component.getName());
                if (!validNodeIds.contains(componentId)) {
                    continue;
                }
                addEdgeIfAbsent(deduped, gateway, componentId, "REST");
                List<String> dependencies = component.getDependencies();
                if (dependencies == null) {
                    continue;
                }
                for (String dependency : dependencies) {
                    if (dependency == null || dependency.isBlank()) {
                        continue;
                    }
                    String dependencyId = slugify(dependency);
                    if (validNodeIds.contains(dependencyId)) {
                        addEdgeIfAbsent(deduped, componentId, dependencyId, "Internal");
                    }
                }
            }
        }

        return new ArrayList<>(deduped.values());
    }

    private DiagramEdge sanitizeEdge(DiagramEdge rawEdge, Set<String> validNodeIds) {
        if (rawEdge == null) {
            return null;
        }
        String source = rawEdge.getSource();
        String target = rawEdge.getTarget();
        if (source == null || source.isBlank()) {
            source = rawEdge.getFrom();
        }
        if (target == null || target.isBlank()) {
            target = rawEdge.getTo();
        }
        source = slugify(source);
        target = slugify(target);
        if (source.isBlank() || target.isBlank() || source.equals(target)) {
            return null;
        }
        if (!validNodeIds.contains(source) || !validNodeIds.contains(target)) {
            return null;
        }

        return DiagramEdge.builder()
                .id(rawEdge.getId() == null || rawEdge.getId().isBlank() ? edgeKey(source, target) : rawEdge.getId())
                .source(source)
                .target(target)
                .from(source)
                .to(target)
                .label(rawEdge.getLabel() == null || rawEdge.getLabel().isBlank() ? "Flow" : rawEdge.getLabel())
                .animated(Boolean.TRUE.equals(rawEdge.getAnimated()))
                .build();
    }

    private void addEdgeIfAbsent(Map<String, DiagramEdge> deduped, String source, String target, String label) {
        if (source == null || target == null || source.isBlank() || target.isBlank() || source.equals(target)) {
            return;
        }
        String key = edgeKey(source, target);
        deduped.putIfAbsent(
                key,
                DiagramEdge.builder()
                        .id(key)
                        .source(source)
                        .target(target)
                        .from(source)
                        .to(target)
                        .label(label)
                        .build()
        );
    }

    private void assignPositions(List<DiagramNode> nodes) {
        Map<String, List<DiagramNode>> byLayer = new LinkedHashMap<>();
        byLayer.put("EDGE", new ArrayList<>());
        byLayer.put("APP", new ArrayList<>());
        byLayer.put("ASYNC", new ArrayList<>());
        byLayer.put("DATA", new ArrayList<>());
        byLayer.put("OPS", new ArrayList<>());

        for (DiagramNode node : nodes) {
            String layer = node.getData() == null ? "APP" : node.getData().getLayer();
            String normalizedLayer = layer == null ? "APP" : layer.toUpperCase();
            byLayer.computeIfAbsent(normalizedLayer, key -> new ArrayList<>()).add(node);
        }

        int layerIndex = 0;
        for (Map.Entry<String, List<DiagramNode>> entry : byLayer.entrySet()) {
            List<DiagramNode> layerNodes = entry.getValue();
            layerNodes.sort(Comparator.comparing(DiagramNode::getId));
            int x = 140 + (layerIndex * BASE_X_SPACING);
            for (int index = 0; index < layerNodes.size(); index++) {
                DiagramNode node = layerNodes.get(index);
                DiagramPosition position = node.getPosition();
                if (position != null && position.getX() != null && position.getY() != null) {
                    continue;
                }
                node.setPosition(
                        DiagramPosition.builder()
                                .x(x)
                                .y(140 + (index * BASE_Y_SPACING))
                                .build()
                );
            }
            layerIndex += 1;
        }
    }

    private String buildMermaid(List<DiagramNode> nodes, List<DiagramEdge> edges) {
        StringBuilder builder = new StringBuilder("flowchart LR\n");
        Map<String, String> idMap = new HashMap<>();
        Map<String, List<String>> classesByType = new LinkedHashMap<>();

        for (DiagramNode node : nodes) {
            String safeId = mermaidSafeId(node.getId());
            idMap.put(node.getId(), safeId);
            String label = node.getData() != null && node.getData().getLabel() != null && !node.getData().getLabel().isBlank()
                    ? node.getData().getLabel()
                    : titleFromId(node.getId());
            builder.append("    ")
                    .append(safeId)
                    .append("[\"")
                    .append(label.replace("\"", "'"))
                    .append("\"]\n");

            String typeClass = mermaidTypeClass(node.getType());
            classesByType.computeIfAbsent(typeClass, key -> new ArrayList<>()).add(safeId);
        }

        for (DiagramEdge edge : edges) {
            String source = idMap.get(edge.getSource() == null ? edge.getFrom() : edge.getSource());
            String target = idMap.get(edge.getTarget() == null ? edge.getTo() : edge.getTarget());
            if (source == null || target == null) {
                continue;
            }
            String label = edge.getLabel() == null || edge.getLabel().isBlank() ? "flow" : edge.getLabel();
            builder.append("    ")
                    .append(source)
                    .append(" -->|")
                    .append(label.replace("|", "/"))
                    .append("| ")
                    .append(target)
                    .append("\n");
        }

        builder.append("\n")
                .append("    classDef gateway fill:#e0f2fe,stroke:#0284c7,color:#0c4a6e;\n")
                .append("    classDef service fill:#e2e8f0,stroke:#334155,color:#0f172a;\n")
                .append("    classDef data fill:#ffedd5,stroke:#c2410c,color:#7c2d12;\n")
                .append("    classDef async fill:#fef3c7,stroke:#b45309,color:#78350f;\n")
                .append("    classDef edge fill:#ede9fe,stroke:#6d28d9,color:#4c1d95;\n")
                .append("    classDef ops fill:#dcfce7,stroke:#15803d,color:#14532d;\n");

        for (Map.Entry<String, List<String>> entry : classesByType.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            builder.append("    class ")
                    .append(String.join(",", entry.getValue()))
                    .append(" ")
                    .append(entry.getKey())
                    .append(";\n");
        }
        return builder.toString();
    }

    private String findNodeId(Set<String> validNodeIds, String... containsAny) {
        for (String id : validNodeIds) {
            String normalized = id.toLowerCase();
            for (String token : containsAny) {
                if (token != null && !token.isBlank() && normalized.contains(token.toLowerCase())) {
                    return id;
                }
            }
        }
        return null;
    }

    private String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "service";
        }
        String normalized = raw.trim().toLowerCase();
        if (normalized.contains("gateway") || normalized.contains("ingress") || normalized.contains("proxy")) {
            return "gateway";
        }
        if (normalized.contains("cdn")) {
            return "cdn";
        }
        if (normalized.contains("client") || normalized.contains("frontend")) {
            return "client";
        }
        if (normalized.contains("auth")) {
            return "service";
        }
        if (normalized.contains("db") || normalized.contains("database") || normalized.contains("postgres") || normalized.contains("mysql")) {
            return "database";
        }
        if (normalized.contains("cache") || normalized.contains("redis")) {
            return "cache";
        }
        if (normalized.contains("queue") || normalized.contains("kafka") || normalized.contains("sqs") || normalized.contains("rabbit")) {
            return "queue";
        }
        if (normalized.contains("worker") || normalized.contains("consumer")) {
            return "worker";
        }
        if (normalized.contains("monitor") || normalized.contains("observability") || normalized.contains("trace")) {
            return "observability";
        }
        if (normalized.contains("storage") || normalized.contains("s3") || normalized.contains("blob")) {
            return "storage";
        }
        return "service";
    }

    private String normalizeLayer(String type) {
        String normalized = type == null ? "" : type.toLowerCase();
        if (normalized.equals("client") || normalized.equals("gateway") || normalized.equals("cdn")) {
            return "EDGE";
        }
        if (normalized.equals("database") || normalized.equals("cache") || normalized.equals("storage")) {
            return "DATA";
        }
        if (normalized.equals("queue") || normalized.equals("worker")) {
            return "ASYNC";
        }
        if (normalized.equals("observability")) {
            return "OPS";
        }
        return "APP";
    }

    private String defaultTechnology(String type) {
        return switch (normalizeType(type)) {
            case "client" -> "Web / Mobile";
            case "cdn" -> "CloudFront / Cloud CDN";
            case "gateway" -> "Kong / Nginx";
            case "database" -> "PostgreSQL";
            case "cache" -> "Redis";
            case "queue" -> "Kafka / SQS";
            case "worker" -> "Spring Workers";
            case "observability" -> "Prometheus / Grafana";
            case "storage" -> "S3 / GCS";
            default -> "Spring Boot";
        };
    }

    private String titleFromId(String id) {
        if (id == null || id.isBlank()) {
            return "Node";
        }
        String[] parts = id.split("-");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(part.substring(0, 1).toUpperCase()).append(part.substring(1));
        }
        return builder.length() == 0 ? id : builder.toString();
    }

    private String slugify(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private String edgeKey(DiagramEdge edge) {
        String source = edge.getSource() == null ? edge.getFrom() : edge.getSource();
        String target = edge.getTarget() == null ? edge.getTo() : edge.getTarget();
        return edgeKey(source, target);
    }

    private String edgeKey(String source, String target) {
        return "edge-" + slugify(source) + "-" + slugify(target);
    }

    private String mermaidSafeId(String id) {
        String safe = id == null ? "node" : id.replaceAll("[^A-Za-z0-9_]", "_");
        if (safe.isBlank()) {
            safe = "node";
        }
        if (Character.isDigit(safe.charAt(0))) {
            safe = "n_" + safe;
        }
        return safe;
    }

    private String mermaidTypeClass(String rawType) {
        String type = normalizeType(rawType);
        if (Objects.equals(type, "gateway")) {
            return "gateway";
        }
        if (Objects.equals(type, "database") || Objects.equals(type, "cache") || Objects.equals(type, "storage")) {
            return "data";
        }
        if (Objects.equals(type, "queue") || Objects.equals(type, "worker")) {
            return "async";
        }
        if (Objects.equals(type, "client") || Objects.equals(type, "cdn")) {
            return "edge";
        }
        if (Objects.equals(type, "observability")) {
            return "ops";
        }
        return "service";
    }

    private DesignStageResult executeStage(
            UUID designId,
            String stageName,
            int progress,
            StageSupplier stageSupplier
    ) {
        designGenerationPublisher.publishStageStarted(designId.toString(), stageName, progress);
        log.info("DesignId={} stage={} started", designId, stageName);
        try {
            DesignStageResult result = stageSupplier.get();
            designGenerationPublisher.publishStageCompleted(
                    designId.toString(),
                    stageName,
                    progress,
                    result.getContent()
            );
            log.info("DesignId={} stage={} completed", designId, stageName);
            return result;
        } catch (Exception ex) {
            designGenerationPublisher.publishStageFailed(designId.toString(), stageName, ex.getMessage());
            log.error("DesignId={} stage={} failed", designId, stageName, ex);
            throw ex;
        }
    }

    @FunctionalInterface
    private interface StageSupplier {
        DesignStageResult get();
    }
}
