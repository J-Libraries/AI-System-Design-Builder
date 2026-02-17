package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.DesignDomain;
import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignStageResult;
import com.aiarch.systemdesign.dto.TargetPlatform;
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
import com.aiarch.systemdesign.dto.document.WireframeScreen;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final TypeReference<List<WireframeScreen>> WIREFRAME_SCREEN_TYPE = new TypeReference<>() { };
    private static final int MIN_API_CONTRACTS = 20;
    private static final int MIN_VISUAL_NODES = 10;
    private static final int MIN_TASKS_PER_MODULE = 12;
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
    @Transactional
    public void initializeDesign(UUID designId, DesignRequestDTO request) {
        SystemDesignDocument placeholderDocument = buildPlaceholderDocument();
        JsonNode requestSnapshot = objectMapper.valueToTree(request);

        SystemDesign existingDesign = systemDesignRepository.findById(designId).orElse(null);
        if (existingDesign != null) {
            existingDesign.setProductName(request.getProductName());
            existingDesign.setVersion(positiveOr(existingDesign.getVersion(), 0) + 1);
            existingDesign.setRequestJson(requestSnapshot);
            existingDesign.setDocumentJson(documentMapper.toJsonNode(placeholderDocument));
            systemDesignRepository.save(existingDesign);
            return;
        }

        int nextVersion = systemDesignRepository.findTopByProductNameOrderByVersionDesc(request.getProductName())
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);

        SystemDesign design = SystemDesign.builder()
                .id(designId)
                .productName(request.getProductName())
                .version(nextVersion)
                .requestJson(requestSnapshot)
                .documentJson(documentMapper.toJsonNode(placeholderDocument))
                .build();
        systemDesignRepository.save(design);
    }

    private SystemDesignDocument buildPlaceholderDocument() {
        return SystemDesignDocument.builder()
                .sow("Scope of work generation in progress.")
                .overview("Design generation is in progress. Please wait for orchestration to complete.")
                .capacityEstimation("Pending generation")
                .hld("Pending generation")
                .components(List.of())
                .lld(List.of())
                .apiContracts(List.of())
                .databaseSchemas(List.of())
                .dataFlowScenarios(List.of())
                .scalingStrategy("Pending generation")
                .failureHandling("Pending generation")
                .tradeoffs("Pending generation")
                .taskBreakdown(List.of())
                .wireframeSummary("Wireframe generation in progress.")
                .wireframeScreens(List.of())
                .diagramMetadata(null)
                .build();
    }

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
            DesignStageResult sow = executeSowStageWithFallback(designId, request);
            DesignStageResult hld = executeStage(designId, "HLD", 14, () -> aiStageService.generateHLD(request));
            DesignStageResult componentBreakdown = executeStage(
                    designId,
                    "COMPONENT_BREAKDOWN",
                    30,
                    () -> aiStageService.generateComponentBreakdown(hld)
            );
            List<Component> componentsForFallback = extractComponentsForFallback(componentBreakdown);
            DesignStageResult lld = executeStage(
                    designId,
                    "LLD",
                    45,
                    () -> aiStageService.generateLLD(componentBreakdown)
            );

            CompletableFuture<DesignStageResult> scalingFuture = CompletableFuture.supplyAsync(
                    () -> executeStage(
                            designId,
                            "SCALING_STRATEGY",
                            75,
                            () -> aiStageService.generateScalingStrategy(hld)
                    ),
                    orchestratorTaskExecutor
            );
            CompletableFuture<DesignStageResult> failureFuture = CompletableFuture.supplyAsync(
                    () -> executeStage(
                            designId,
                            "FAILURE_HANDLING",
                            85,
                            () -> aiStageService.generateFailureHandling(hld)
                    ),
                    orchestratorTaskExecutor
            );

            DesignStageResult dataFlow = executeStage(
                    designId,
                    "DATA_FLOW",
                    60,
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
            DesignStageResult wireframe = executeWireframeStageWithFallback(
                    designId,
                    hld,
                    componentBreakdown,
                    lld
            );
            DesignStageResult scaling = scalingFuture.join();
            DesignStageResult failureHandling = failureFuture.join();

            SystemDesignDocument document = buildDocument(
                    sow.getContent(),
                    hld.getContent(),
                    componentBreakdown.getContent(),
                    lld.getContent(),
                    dataFlow.getContent(),
                    scaling.getContent(),
                    failureHandling.getContent(),
                    diagramMetadata.getContent(),
                    taskBreakdown.getContent(),
                    wireframe.getContent(),
                    request
            );

            JsonNode finalDocument = documentMapper.toJsonNode(document);
            JsonNode requestSnapshot = objectMapper.valueToTree(request);
            SystemDesign systemDesign = systemDesignRepository.findById(designId)
                    .orElseGet(() -> SystemDesign.builder()
                            .id(designId)
                            .productName(request.getProductName())
                            .version(
                                    systemDesignRepository.findTopByProductNameOrderByVersionDesc(request.getProductName())
                                            .map(existing -> existing.getVersion() + 1)
                                            .orElse(1)
                            )
                            .requestJson(requestSnapshot)
                            .build());
            systemDesign.setProductName(request.getProductName());
            systemDesign.setRequestJson(requestSnapshot);
            systemDesign.setDocumentJson(finalDocument);
            systemDesignRepository.save(systemDesign);
            designGenerationPublisher.publishStageCompleted(
                    designId.toString(),
                    "ORCHESTRATION",
                    100,
                    finalDocument.toString()
            );

            log.info(
                    "Completed orchestration for designId={} product={} version={}",
                    designId,
                    request.getProductName(),
                    systemDesign.getVersion()
            );
            return CompletableFuture.completedFuture(null);
        } catch (Exception ex) {
            log.error("Design orchestration failed for designId={}", designId, ex);
            upsertFailureDocument(designId, request, ex);
            designGenerationPublisher.publishStageFailed(
                    designId.toString(),
                    "ORCHESTRATION",
                    ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "Unknown error" : ex.getMessage())
            );
            return CompletableFuture.failedFuture(ex);
        }
    }

    private SystemDesignDocument buildDocument(
            String sowJson,
            String hldJson,
            String componentJson,
            String lldJson,
            String dataFlowJson,
            String scalingJson,
            String failureJson,
            String diagramJson,
            String taskBreakdownJson,
            String wireframeJson,
            DesignRequestDTO request
    ) {
        JsonNode sowNode = readJson(sowJson);
        JsonNode hldNode = readJson(hldJson);
        JsonNode componentNode = readJson(componentJson);
        JsonNode lldNode = readJson(lldJson);
        JsonNode dataFlowNode = readJson(dataFlowJson);
        JsonNode scalingNode = readJson(scalingJson);
        JsonNode failureNode = readJson(failureJson);
        JsonNode diagramNode = readJson(diagramJson);
        JsonNode taskBreakdownNode = readJson(taskBreakdownJson);
        JsonNode wireframeNode = readJson(wireframeJson);

        List<Component> components = enrichComponents(readList(componentNode, "components", COMPONENT_LIST_TYPE));
        List<ApiContract> apiContracts = readList(hldNode, "api_contracts", API_CONTRACT_LIST_TYPE);
        List<ApiContract> enrichedApiContracts = ensureRichApiContracts(apiContracts, components);
        DiagramMetadata diagramMetadata = enrichDiagramMetadata(readObject(diagramNode, DiagramMetadata.class), components);
        List<TaskBreakdownItem> taskBreakdown = normalizeTaskBreakdown(readList(taskBreakdownNode, "task_breakdown", TASK_BREAKDOWN_TYPE));
        if (taskBreakdown.isEmpty()) {
            taskBreakdown = buildFallbackTaskBreakdown(components);
        }

        SystemDesignDocument document = SystemDesignDocument.builder()
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
                .sow(resolveSowText(sowNode))
                .wireframeSummary(resolveWireframeSummary(wireframeNode))
                .wireframeScreens(resolveWireframeScreens(wireframeNode))
                .diagramMetadata(diagramMetadata)
                .build();

        return enrichDocumentForRequest(document, request);
    }

    private SystemDesignDocument enrichDocumentForRequest(SystemDesignDocument document, DesignRequestDTO request) {
        if (document == null || request == null || !isMobileContext(request)) {
            return document;
        }

        boolean cameraRequested = isCameraRequested(request);
        if (!cameraRequested) {
            return document;
        }

        List<Component> components = document.getComponents() == null
                ? new ArrayList<>()
                : new ArrayList<>(document.getComponents());
        components = ensureCameraComponentCoverage(components);
        components = enrichComponents(components);
        document.setComponents(components);

        List<ComponentLLD> lld = document.getLld() == null
                ? new ArrayList<>()
                : new ArrayList<>(document.getLld());
        lld = ensureCameraLldCoverage(lld);
        document.setLld(lld);

        List<ApiContract> apiContracts = document.getApiContracts() == null
                ? new ArrayList<>()
                : new ArrayList<>(document.getApiContracts());
        apiContracts = ensureCameraApiCoverage(apiContracts);
        document.setApiContracts(ensureRichApiContracts(apiContracts, components));

        List<TaskBreakdownItem> taskBreakdown = document.getTaskBreakdown() == null
                ? new ArrayList<>()
                : new ArrayList<>(document.getTaskBreakdown());
        taskBreakdown = ensureCameraTaskBreakdownCoverage(taskBreakdown);
        document.setTaskBreakdown(normalizeTaskBreakdown(taskBreakdown));

        List<WireframeScreen> wireframeScreens = document.getWireframeScreens() == null
                ? new ArrayList<>()
                : new ArrayList<>(document.getWireframeScreens());
        wireframeScreens = ensureCameraWireframeCoverage(wireframeScreens);
        document.setWireframeScreens(wireframeScreens);

        document.setHld(appendCameraHldDetail(document.getHld()));
        return document;
    }

    private boolean isMobileContext(DesignRequestDTO request) {
        if (request == null) {
            return false;
        }
        if (request.getDesignDomain() == DesignDomain.MOBILE) {
            return true;
        }
        return request.getTargetPlatform() == TargetPlatform.MOBILE
                || request.getTargetPlatform() == TargetPlatform.BOTH;
    }

    private boolean isCameraRequested(DesignRequestDTO request) {
        if (request == null) {
            return false;
        }
        if (containsAnyCameraKeyword(request.getProductName())) {
            return true;
        }
        if (request.getFunctionalRequirements() != null) {
            for (String requirement : request.getFunctionalRequirements()) {
                if (containsAnyCameraKeyword(requirement)) {
                    return true;
                }
            }
        }
        if (request.getNonFunctionalRequirements() != null) {
            for (String requirement : request.getNonFunctionalRequirements()) {
                if (containsAnyCameraKeyword(requirement)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsAnyCameraKeyword(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase();
        return normalized.contains("camera")
                || normalized.contains("photo")
                || normalized.contains("image capture")
                || normalized.contains("capture")
                || normalized.contains("video")
                || normalized.contains("reel")
                || normalized.contains("scan")
                || normalized.contains("qr");
    }

    private List<Component> ensureCameraComponentCoverage(List<Component> components) {
        List<Component> result = components == null ? new ArrayList<>() : new ArrayList<>(components);
        int existingIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            Component component = result.get(i);
            if (component != null && containsWord(component.getName(), "camera")) {
                existingIndex = i;
                break;
            }
        }

        int nextOrder = 1;
        for (Component component : result) {
            nextOrder = Math.max(nextOrder, positiveOr(component == null ? null : component.getBuildOrder(), 0) + 1);
        }

        String cameraResponsibility = "Own camera capture lifecycle for mobile clients including permission checks, "
                + "device capability discovery, autofocus/manual focus, ISO and shutter-speed control, flash modes, "
                + "capture quality presets, image processing/compression, metadata creation, and resilient upload handoff.";
        String cameraApproach = "Implement as a dedicated camera domain module with capability adapters (CameraX/AVFoundation), "
                + "state machine for preview/focus/capture, exposure controller, processing pipeline, and background upload queue. "
                + "Include feature flags for advanced controls, telemetry for latency/failure rates, and graceful fallback for unsupported devices.";
        List<String> cameraDependencies = List.of(
                "Permission Service",
                "Device Capability Service",
                "Media Processing Service",
                "Upload Queue",
                "Observability Service"
        );

        if (existingIndex >= 0) {
            Component existing = result.get(existingIndex);
            if (existing == null) {
                existing = Component.builder().build();
            }
            existing.setName(existing.getName() == null || existing.getName().isBlank() ? "Camera Module" : existing.getName());
            existing.setType(existing.getType() == null || existing.getType().isBlank() ? "mobile_feature" : existing.getType());
            if (existing.getResponsibility() == null || existing.getResponsibility().length() < 90) {
                existing.setResponsibility(cameraResponsibility);
            }
            if (existing.getImplementationApproach() == null || existing.getImplementationApproach().length() < 120) {
                existing.setImplementationApproach(cameraApproach);
            }
            existing.setDependencies(mergeUnique(existing.getDependencies(), cameraDependencies));
            if (existing.getBuildOrder() == null || existing.getBuildOrder() <= 0) {
                existing.setBuildOrder(nextOrder);
            }
            result.set(existingIndex, existing);
            return result;
        }

        result.add(Component.builder()
                .name("Camera Module")
                .type("mobile_feature")
                .responsibility(cameraResponsibility)
                .implementationApproach(cameraApproach)
                .buildOrder(nextOrder)
                .dependencies(cameraDependencies)
                .build());
        return result;
    }

    private List<ComponentLLD> ensureCameraLldCoverage(List<ComponentLLD> lldList) {
        List<ComponentLLD> result = lldList == null ? new ArrayList<>() : new ArrayList<>(lldList);
        int existingIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            ComponentLLD lld = result.get(i);
            if (lld != null && containsWord(lld.getComponentName(), "camera")) {
                existingIndex = i;
                break;
            }
        }

        List<String> classes = List.of(
                "CameraPermissionManager (permission orchestration + rationale flow)",
                "DeviceCapabilityService (supported lens/mode/ISO/shutter range detection)",
                "CaptureSessionManager (preview lifecycle and camera session state machine)",
                "FocusController (autofocus + tap-to-focus + lock)",
                "ExposureController (ISO + shutter speed + exposure compensation)",
                "FlashController (off/auto/on/torch mode handling)",
                "ImageProcessingPipeline (crop/rotate/compress/color-profile pipeline)",
                "CaptureMetadataStore (EXIF + capture context persistence)",
                "UploadOrchestrator (background upload with retry/backoff)"
        );
        List<String> interfaces = List.of(
                "CameraDeviceAdapter",
                "CaptureControlApi",
                "ImageProcessor",
                "MediaUploadClient",
                "CaptureTelemetryPublisher"
        );
        List<String> sequence = List.of(
                "Client opens camera screen and CameraPermissionManager validates permission state.",
                "DeviceCapabilityService loads camera features and available control ranges.",
                "CaptureSessionManager initializes preview session with selected lens/profile.",
                "FocusController configures autofocus mode and handles manual focus gestures.",
                "ExposureController applies ISO/shutter values and validates supported bounds.",
                "FlashController applies selected flash mode and sync settings.",
                "User captures frame and ImageProcessingPipeline performs normalize/compress transforms.",
                "CaptureMetadataStore persists capture metadata and diagnostic timing values.",
                "UploadOrchestrator enqueues media upload job with idempotency key.",
                "MediaUploadClient sends payload to media backend and tracks progress.",
                "Retry policy handles transient failures and resumes on network recovery.",
                "CaptureTelemetryPublisher emits success/failure metrics and latency traces."
        );
        String description = "Implements production-ready mobile camera stack with deterministic control flow for permissions, "
                + "camera lifecycle, focus/exposure/ISO/shutter/flash controls, image transformation, metadata persistence, "
                + "reliable upload handoff, and observability instrumentation.";

        if (existingIndex >= 0) {
            ComponentLLD existing = result.get(existingIndex);
            if (existing == null) {
                existing = ComponentLLD.builder().build();
            }
            existing.setComponentName(existing.getComponentName() == null || existing.getComponentName().isBlank()
                    ? "Camera Module"
                    : existing.getComponentName());
            if (existing.getModuleDescription() == null || existing.getModuleDescription().length() < 120) {
                existing.setModuleDescription(description);
            }
            existing.setClasses(mergeUnique(existing.getClasses(), classes));
            existing.setInterfaces(mergeUnique(existing.getInterfaces(), interfaces));
            existing.setSequence(mergeUnique(existing.getSequence(), sequence));
            result.set(existingIndex, existing);
            return result;
        }

        result.add(ComponentLLD.builder()
                .componentName("Camera Module")
                .moduleDescription(description)
                .classes(classes)
                .interfaces(interfaces)
                .sequence(sequence)
                .build());
        return result;
    }

    private List<ApiContract> ensureCameraApiCoverage(List<ApiContract> apiContracts) {
        List<ApiContract> result = apiContracts == null ? new ArrayList<>() : new ArrayList<>(apiContracts);
        upsertApiContract(result, ApiContract.builder()
                .name("Get Camera Capture Config")
                .method("GET")
                .path("/api/v1/media/capture-config")
                .requestSchema("{\"device_type\":\"string\",\"app_version\":\"string\"}")
                .responseSchema("{\"max_resolution\":\"string\",\"supported_flash_modes\":[\"string\"],\"iso_range\":\"string\",\"shutter_range\":\"string\"}")
                .errorCodes(List.of("400", "401", "429", "500"))
                .build());
        upsertApiContract(result, ApiContract.builder()
                .name("Create Capture Session")
                .method("POST")
                .path("/api/v1/media/captures/session")
                .requestSchema("{\"user_id\":\"uuid\",\"device_info\":\"object\",\"capture_preset\":\"string\"}")
                .responseSchema("{\"session_id\":\"string\",\"upload_policy\":\"object\",\"expires_at\":\"timestamp\"}")
                .errorCodes(List.of("400", "401", "403", "429", "500"))
                .build());
        upsertApiContract(result, ApiContract.builder()
                .name("Finalize Capture Metadata")
                .method("POST")
                .path("/api/v1/media/captures/{sessionId}/finalize")
                .requestSchema("{\"metadata\":\"object\",\"image_hash\":\"string\",\"processing_profile\":\"string\"}")
                .responseSchema("{\"capture_id\":\"string\",\"status\":\"string\"}")
                .errorCodes(List.of("400", "401", "404", "409", "500"))
                .build());
        upsertApiContract(result, ApiContract.builder()
                .name("Request Upload URL")
                .method("POST")
                .path("/api/v1/media/uploads/presign")
                .requestSchema("{\"content_type\":\"string\",\"size_bytes\":0,\"checksum\":\"string\"}")
                .responseSchema("{\"upload_url\":\"string\",\"storage_key\":\"string\",\"expires_at\":\"timestamp\"}")
                .errorCodes(List.of("400", "401", "413", "429", "500"))
                .build());
        return result;
    }

    private void upsertApiContract(List<ApiContract> target, ApiContract candidate) {
        if (candidate == null || candidate.getPath() == null || candidate.getPath().isBlank()) {
            return;
        }
        String candidateMethod = normalizeMethod(candidate.getMethod());
        String candidatePath = normalizePath(candidate.getPath());
        for (int i = 0; i < target.size(); i++) {
            ApiContract existing = target.get(i);
            if (existing == null) {
                continue;
            }
            if (candidateMethod.equals(normalizeMethod(existing.getMethod()))
                    && candidatePath.equals(normalizePath(existing.getPath()))) {
                target.set(i, mergeApiContract(existing, candidate));
                return;
            }
        }
        target.add(candidate);
    }

    private ApiContract mergeApiContract(ApiContract original, ApiContract overlay) {
        if (original == null) {
            return overlay;
        }
        if (overlay == null) {
            return original;
        }
        return ApiContract.builder()
                .name(isBlank(original.getName()) ? overlay.getName() : original.getName())
                .method(isBlank(original.getMethod()) ? overlay.getMethod() : original.getMethod())
                .path(isBlank(original.getPath()) ? overlay.getPath() : original.getPath())
                .requestSchema(isBlank(original.getRequestSchema()) ? overlay.getRequestSchema() : original.getRequestSchema())
                .responseSchema(isBlank(original.getResponseSchema()) ? overlay.getResponseSchema() : original.getResponseSchema())
                .errorCodes(mergeUnique(original.getErrorCodes(), overlay.getErrorCodes()))
                .build();
    }

    private List<TaskBreakdownItem> ensureCameraTaskBreakdownCoverage(List<TaskBreakdownItem> taskBreakdown) {
        List<TaskBreakdownItem> result = taskBreakdown == null ? new ArrayList<>() : new ArrayList<>(taskBreakdown);
        int existingIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            TaskBreakdownItem item = result.get(i);
            if (item != null && containsWord(item.getModuleName(), "camera")) {
                existingIndex = i;
                break;
            }
        }

        List<TaskBreakdownTask> cameraTasks = buildCameraModuleTasks();
        if (existingIndex >= 0) {
            TaskBreakdownItem existing = result.get(existingIndex);
            if (existing == null) {
                existing = TaskBreakdownItem.builder().build();
            }
            existing.setModuleName(isBlank(existing.getModuleName()) ? "Camera Module" : existing.getModuleName());
            existing.setImplementationApproach(
                    isBlank(existing.getImplementationApproach())
                            ? "Implement camera stack with layered controllers (permissions, capabilities, controls, capture, processing, upload) and strict observability."
                            : existing.getImplementationApproach()
            );
            existing.setTasks(mergeTaskRows(existing.getTasks(), cameraTasks));
            existing.setHoursExperiencedDeveloper(sumTaskHours(existing.getTasks(), Role.EXPERIENCED));
            existing.setHoursMidLevelDeveloper(sumTaskHours(existing.getTasks(), Role.MID));
            existing.setHoursFresher(sumTaskHours(existing.getTasks(), Role.FRESHER));
            result.set(existingIndex, existing);
            return result;
        }

        TaskBreakdownItem item = TaskBreakdownItem.builder()
                .moduleName("Camera Module")
                .implementationApproach("Build camera feature as an independent module with capability detection, control plane (focus/ISO/shutter/flash), capture pipeline, media upload orchestration, and runtime telemetry.")
                .tasks(cameraTasks)
                .hoursExperiencedDeveloper(sumTaskHours(cameraTasks, Role.EXPERIENCED))
                .hoursMidLevelDeveloper(sumTaskHours(cameraTasks, Role.MID))
                .hoursFresher(sumTaskHours(cameraTasks, Role.FRESHER))
                .build();
        result.add(item);
        return result;
    }

    private List<TaskBreakdownTask> buildCameraModuleTasks() {
        return List.of(
                task("Camera requirement decomposition", "Define camera feature matrix: modes, device support, UX constraints, and acceptance criteria.", 10),
                task("Permissions and privacy workflow", "Implement runtime permission flow, denial recovery, and privacy notices.", 12),
                task("Camera session lifecycle", "Implement camera session startup/shutdown, background handling, and lifecycle resilience.", 16),
                task("Device capability discovery", "Detect lens modes, frame rates, supported ISO/shutter ranges, and stabilization support.", 12),
                task("Focus control module", "Implement autofocus, manual focus gestures, focus lock, and focus failure recovery.", 14),
                task("Exposure controls (ISO + shutter)", "Implement ISO/shutter tuning controls with guardrails and real-time preview adjustments.", 18),
                task("Flash control modes", "Implement flash off/auto/on/torch logic with capability checks and fallback behavior.", 10),
                task("Image processing pipeline", "Build transform pipeline for orientation correction, compression, and quality profile selection.", 16),
                task("Capture metadata persistence", "Store EXIF/context metadata and enforce idempotent capture records.", 10),
                task("Upload queue orchestration", "Implement resilient upload queue with retry/backoff, offline replay, and deduplication.", 16),
                task("Camera telemetry and alerts", "Instrument capture latency, failure counters, and device-specific error signals.", 10),
                task("Device compatibility and performance testing", "Run compatibility matrix tests and optimize battery, memory, and thermal behavior.", 24),
                task("Crash and recovery hardening", "Add defensive error handling, safe fallback states, and recovery UX.", 10)
        );
    }

    private List<TaskBreakdownTask> mergeTaskRows(List<TaskBreakdownTask> existingTasks, List<TaskBreakdownTask> requiredTasks) {
        Map<String, TaskBreakdownTask> byTask = new LinkedHashMap<>();
        if (existingTasks != null) {
            for (TaskBreakdownTask task : existingTasks) {
                if (task == null || isBlank(task.getTaskName())) {
                    continue;
                }
                byTask.put(taskKey(task.getTaskName()), task);
            }
        }
        for (TaskBreakdownTask task : requiredTasks) {
            if (task == null || isBlank(task.getTaskName())) {
                continue;
            }
            byTask.putIfAbsent(taskKey(task.getTaskName()), task);
        }
        return new ArrayList<>(byTask.values());
    }

    private List<WireframeScreen> ensureCameraWireframeCoverage(List<WireframeScreen> screens) {
        List<WireframeScreen> result = screens == null ? new ArrayList<>() : new ArrayList<>(screens);
        for (WireframeScreen screen : result) {
            if (screen != null && containsWord(screen.getScreenName(), "camera")) {
                if (screen.getUiComponents() == null || screen.getUiComponents().size() < 6) {
                    screen.setUiComponents(mergeUnique(
                            screen.getUiComponents(),
                            List.of("Preview surface", "Flash toggle", "ISO slider", "Shutter speed dial", "Focus reticle", "Capture button", "Upload status pill")
                    ));
                }
                if (screen.getInteractions() == null || screen.getInteractions().size() < 5) {
                    screen.setInteractions(mergeUnique(
                            screen.getInteractions(),
                            List.of("Tap to focus", "Adjust ISO", "Adjust shutter speed", "Toggle flash mode", "Capture image", "Retry upload")
                    ));
                }
                return result;
            }
        }

        result.add(WireframeScreen.builder()
                .screenName("Camera Capture")
                .platform("Mobile")
                .purpose("Capture media with advanced camera controls and reliable upload handoff.")
                .layoutDescription("Fullscreen preview with top control bar (flash, settings), center focus grid, bottom control tray (ISO, shutter speed, capture, gallery), and upload status rail.")
                .uiComponents(List.of("Preview surface", "Flash toggle", "ISO slider", "Shutter speed dial", "Focus reticle", "Capture button", "Upload status pill"))
                .interactions(List.of("Tap to focus", "Pinch to zoom", "Adjust ISO", "Adjust shutter speed", "Toggle flash mode", "Capture image", "Retry upload"))
                .apiBindings(List.of(
                        "GET /api/v1/media/capture-config",
                        "POST /api/v1/media/captures/session",
                        "POST /api/v1/media/uploads/presign",
                        "POST /api/v1/media/captures/{sessionId}/finalize"
                ))
                .build());
        return result;
    }

    private String appendCameraHldDetail(String hldText) {
        String base = hldText == null ? "" : hldText.trim();
        if (base.toLowerCase().contains("camera module")) {
            return base;
        }
        String cameraDetail = """
                
                Camera Module Deep Dive:
                - Control Plane: permission manager, capability detection, focus/exposure controller, flash manager.
                - Capture Plane: preview session lifecycle, frame capture pipeline, image processing/compression.
                - Reliability: bounded retry upload queue, offline replay, idempotent capture finalization.
                - Observability: capture latency histogram, device capability mismatch counters, upload failure rates.
                - Quality Gates: device compatibility matrix, thermal/battery guardrails, crash-safe recovery states.
                """;
        return (base + cameraDetail).trim();
    }

    private boolean containsWord(String source, String word) {
        if (source == null || word == null) {
            return false;
        }
        return source.toLowerCase().contains(word.toLowerCase());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<String> mergeUnique(List<String> existing, List<String> additions) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            for (String item : existing) {
                if (item != null && !item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }
        if (additions != null) {
            for (String item : additions) {
                if (item != null && !item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }
        return new ArrayList<>(merged);
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

    private void upsertFailureDocument(UUID designId, DesignRequestDTO request, Exception ex) {
        SystemDesignDocument failureDocument = SystemDesignDocument.builder()
                .sow("SOW generation failed. Please retry orchestration for this design.")
                .overview("Design orchestration failed before completion.")
                .capacityEstimation("Not available due to orchestration failure.")
                .hld("Not available due to orchestration failure.")
                .components(List.of())
                .lld(List.of())
                .apiContracts(List.of())
                .databaseSchemas(List.of())
                .dataFlowScenarios(List.of())
                .scalingStrategy("Not available due to orchestration failure.")
                .failureHandling("Not available due to orchestration failure.")
                .tradeoffs("Error: " + (ex.getMessage() == null ? "Unknown orchestration error" : ex.getMessage()))
                .taskBreakdown(List.of())
                .wireframeSummary("Not available due to orchestration failure.")
                .wireframeScreens(List.of())
                .diagramMetadata(null)
                .build();
        JsonNode requestSnapshot = objectMapper.valueToTree(request);

        SystemDesign design = systemDesignRepository.findById(designId)
                .orElseGet(() -> SystemDesign.builder()
                        .id(designId)
                        .productName(request.getProductName())
                        .version(
                                systemDesignRepository.findTopByProductNameOrderByVersionDesc(request.getProductName())
                                        .map(existing -> existing.getVersion() + 1)
                                        .orElse(1)
                        )
                        .requestJson(requestSnapshot)
                        .build());
        design.setRequestJson(requestSnapshot);
        design.setDocumentJson(documentMapper.toJsonNode(failureDocument));
        systemDesignRepository.save(design);
    }

    private String resolveSowText(JsonNode sowNode) {
        if (sowNode == null || sowNode.isMissingNode() || sowNode.isNull()) {
            return "";
        }
        JsonNode section = sowNode.path("sow");
        if (section.isMissingNode() || section.isNull() || !section.isObject()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Scope Of Work\n");
        appendSection(builder, "1. Project Summary", getText(section, "project_summary"));
        appendListSection(builder, "2. Business Objectives", readStringArray(section.path("business_objectives")));
        appendListSection(builder, "3. In Scope", readStringArray(section.path("in_scope")));
        appendListSection(builder, "4. Out Of Scope", readStringArray(section.path("out_of_scope")));
        appendListSection(builder, "5. Dependencies", readStringArray(section.path("dependencies")));
        appendListSection(builder, "6. Deliverables", readStringArray(section.path("deliverables")));
        appendListSection(builder, "7. Milestones", readStringArray(section.path("milestones")));
        appendListSection(builder, "8. Acceptance Criteria", readStringArray(section.path("acceptance_criteria")));
        appendListSection(builder, "9. Risks", readStringArray(section.path("risks")));
        appendListSection(builder, "10. Assumptions", readStringArray(section.path("assumptions")));
        return builder.toString().trim();
    }

    private String resolveWireframeSummary(JsonNode wireframeNode) {
        return getText(wireframeNode, "wireframe_summary");
    }

    private List<WireframeScreen> resolveWireframeScreens(JsonNode wireframeNode) {
        List<WireframeScreen> screens = readList(wireframeNode, "screens", WIREFRAME_SCREEN_TYPE);
        if (screens.isEmpty()) {
            return buildFallbackWireframeScreens(List.of());
        }
        return screens;
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
                    Component.builder().name("Product Discovery & Planning").type("foundation").build(),
                    Component.builder().name("Identity & Access Management").type("auth").build(),
                    Component.builder().name("Core Business Services").type("service").build(),
                    Component.builder().name("Data Platform & Integrations").type("database").build(),
                    Component.builder().name("DevOps, Monitoring & Release").type("devops").build()
            );
        }
        List<TaskBreakdownItem> items = new ArrayList<>();
        for (Component component : sourceComponents) {
            if (component == null || component.getName() == null || component.getName().isBlank()) {
                continue;
            }
            String componentType = inferComponentType(component.getType(), component.getName());
            int base = estimateBaseHours(component);
            List<TaskBreakdownTask> tasks = ensureDetailedTaskCoverage(
                    defaultTasksForComponent(component.getName(), componentType, base),
                    component.getName(),
                    componentType,
                    base
            );
            items.add(TaskBreakdownItem.builder()
                    .moduleName(component.getName())
                    .implementationApproach(
                            component.getImplementationApproach() == null || component.getImplementationApproach().isBlank()
                                    ? "Implement in phased increments with contract-first APIs, automated tests, and production-readiness checks."
                                    : component.getImplementationApproach()
                    )
                    .tasks(tasks)
                    .hoursExperiencedDeveloper(sumTaskHours(tasks, Role.EXPERIENCED))
                    .hoursMidLevelDeveloper(sumTaskHours(tasks, Role.MID))
                    .hoursFresher(sumTaskHours(tasks, Role.FRESHER))
                    .build());
        }
        return items;
    }

    private int estimateBaseHours(Component component) {
        int dependencyWeight = component.getDependencies() == null ? 0 : component.getDependencies().size() * 6;
        String componentType = inferComponentType(component.getType(), component.getName());
        int complexityWeight = switch (componentType) {
            case "database", "queue", "cache" -> 56;
            case "gateway", "auth" -> 60;
            case "mobile", "frontend" -> 52;
            case "devops", "observability" -> 50;
            default -> 48;
        };
        int responsibilityWeight = component.getResponsibility() == null || component.getResponsibility().isBlank() ? 0 : 6;
        return Math.max(36, complexityWeight + dependencyWeight + responsibilityWeight);
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

            String moduleType = inferComponentType("", item.getModuleName());
            int base = Math.max(40, positiveOr(item.getHoursExperiencedDeveloper(), 0));
            List<TaskBreakdownTask> tasks = item.getTasks();
            if (tasks == null || tasks.isEmpty()) {
                tasks = defaultTasksForComponent(item.getModuleName(), moduleType, base);
            } else {
                List<TaskBreakdownTask> sanitizedTasks = new ArrayList<>();
                for (TaskBreakdownTask task : tasks) {
                    if (task == null || task.getTaskName() == null || task.getTaskName().isBlank()) {
                        continue;
                    }
                    sanitizedTasks.add(TaskBreakdownTask.builder()
                            .taskName(task.getTaskName())
                            .description(task.getDescription() == null || task.getDescription().isBlank()
                                    ? "Implement and verify task completion."
                                    : task.getDescription())
                            .hoursExperiencedDeveloper(Math.max(4, positiveOr(task.getHoursExperiencedDeveloper(), 4)))
                            .hoursMidLevelDeveloper(Math.max(6, positiveOr(task.getHoursMidLevelDeveloper(), 6)))
                            .hoursFresher(Math.max(8, positiveOr(task.getHoursFresher(), 8)))
                            .build());
                }
                tasks = sanitizedTasks;
            }

            tasks = ensureDetailedTaskCoverage(tasks, item.getModuleName(), moduleType, base);
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
                    .hoursExperiencedDeveloper(experiencedTotal)
                    .hoursMidLevelDeveloper(midTotal)
                    .hoursFresher(fresherTotal)
                    .build());
        }
        return normalized;
    }

    private List<TaskBreakdownTask> defaultTasksForComponent(String moduleName, String componentType, int baseHours) {
        List<TaskBreakdownTask> tasks = new ArrayList<>();
        tasks.add(task("Requirement decomposition", "Break down " + moduleName + " stories, edge cases, and acceptance criteria.", hours(baseHours, 0.10, 5)));
        tasks.add(task("Technical design & contracts", "Define module contracts, request/response models, and error model.", hours(baseHours, 0.10, 5)));
        tasks.add(task("Implementation scaffolding", "Create module structure, config, dependency wiring, and guardrails.", hours(baseHours, 0.12, 5)));
        tasks.add(task("Core use-case implementation", "Implement core workflows and business rules for " + moduleName + ".", hours(baseHours, 0.20, 9)));
        tasks.add(task("Validation and error handling", "Implement input validation, retries, and deterministic failure paths.", hours(baseHours, 0.08, 4)));
        tasks.add(task("Integration wiring", "Integrate upstream/downstream modules and third-party dependencies.", hours(baseHours, 0.12, 6)));
        tasks.add(task("Observability instrumentation", "Add structured logs, metrics, traces, and dashboard signals.", hours(baseHours, 0.08, 4)));
        tasks.add(task("Automated testing", "Implement unit, integration, and negative-path tests.", hours(baseHours, 0.12, 6)));
        tasks.add(task("Performance and reliability tuning", "Profile bottlenecks, optimize hotspots, and validate scale behavior.", hours(baseHours, 0.10, 5)));
        tasks.add(task("Documentation and handoff", "Prepare operational notes, API docs, and developer handoff details.", hours(baseHours, 0.08, 4)));

        if (componentType.equals("mobile") || componentType.equals("frontend")) {
            tasks.add(task("Responsive UI states", "Build loading/empty/error/offline states and accessibility support.", hours(baseHours, 0.10, 5)));
            tasks.add(task("Offline sync & local storage", "Implement local cache strategy, sync queue, and conflict handling.", hours(baseHours, 0.12, 6)));
            tasks.add(task("Release packaging", "Prepare store/release builds, feature flags, and runtime config handling.", hours(baseHours, 0.08, 4)));
        }
        if (componentType.equals("auth") || componentType.equals("gateway")) {
            tasks.add(task("Security policy implementation", "Implement authentication, authorization, throttling, and abuse checks.", hours(baseHours, 0.12, 6)));
            tasks.add(task("Audit & compliance controls", "Implement audit logging, sensitive-data masking, and policy checks.", hours(baseHours, 0.08, 4)));
        }
        if (componentType.equals("database") || componentType.equals("cache") || componentType.equals("queue")) {
            tasks.add(task("Schema/index optimization", "Design schema/indexes/retention strategy and migration scripts.", hours(baseHours, 0.14, 7)));
            tasks.add(task("Backup and recovery setup", "Define backup cadence, restore validation, and data integrity checks.", hours(baseHours, 0.08, 4)));
        }
        if (componentType.equals("devops") || componentType.equals("observability")) {
            tasks.add(task("CI/CD pipeline setup", "Create build/test/deploy pipeline with environment promotion gates.", hours(baseHours, 0.14, 7)));
            tasks.add(task("Infrastructure automation", "Provision environments using Infrastructure as Code modules.", hours(baseHours, 0.12, 6)));
            tasks.add(task("Runbook and on-call setup", "Define SLO alerts, runbooks, and incident response workflow.", hours(baseHours, 0.10, 5)));
        }
        return tasks;
    }

    private List<TaskBreakdownTask> ensureDetailedTaskCoverage(
            List<TaskBreakdownTask> tasks,
            String moduleName,
            String componentType,
            int baseHours
    ) {
        Map<String, TaskBreakdownTask> byTaskName = new LinkedHashMap<>();
        if (tasks != null) {
            for (TaskBreakdownTask task : tasks) {
                if (task == null || task.getTaskName() == null || task.getTaskName().isBlank()) {
                    continue;
                }
                TaskBreakdownTask normalized = TaskBreakdownTask.builder()
                        .taskName(task.getTaskName().trim())
                        .description(task.getDescription() == null || task.getDescription().isBlank()
                                ? "Implement and validate the task end-to-end."
                                : task.getDescription().trim())
                        .hoursExperiencedDeveloper(Math.max(4, positiveOr(task.getHoursExperiencedDeveloper(), 4)))
                        .hoursMidLevelDeveloper(Math.max(6, positiveOr(task.getHoursMidLevelDeveloper(), 6)))
                        .hoursFresher(Math.max(8, positiveOr(task.getHoursFresher(), 8)))
                        .build();
                byTaskName.putIfAbsent(taskKey(normalized.getTaskName()), normalized);
            }
        }

        for (TaskBreakdownTask baseline : defaultTasksForComponent(moduleName, componentType, baseHours)) {
            byTaskName.putIfAbsent(taskKey(baseline.getTaskName()), baseline);
        }

        if (byTaskName.size() < MIN_TASKS_PER_MODULE) {
            List<TaskBreakdownTask> expansion = List.of(
                    task("Code review and refactoring", "Address review findings and improve maintainability.", hours(baseHours, 0.06, 4)),
                    task("QA/UAT support", "Support QA validation, defect triage, and acceptance sign-off.", hours(baseHours, 0.06, 4)),
                    task("Production readiness checklist", "Verify security, performance, backup, and monitoring readiness.", hours(baseHours, 0.06, 4)),
                    task("Deployment verification", "Validate post-deployment health checks and rollback safety.", hours(baseHours, 0.05, 3))
            );
            for (TaskBreakdownTask task : expansion) {
                byTaskName.putIfAbsent(taskKey(task.getTaskName()), task);
                if (byTaskName.size() >= MIN_TASKS_PER_MODULE) {
                    break;
                }
            }
        }
        return new ArrayList<>(byTaskName.values());
    }

    private TaskBreakdownTask task(String name, String description, int experiencedHours) {
        int normalizedExperienced = Math.max(3, experiencedHours);
        return TaskBreakdownTask.builder()
                .taskName(name)
                .description(description)
                .hoursExperiencedDeveloper(normalizedExperienced)
                .hoursMidLevelDeveloper(Math.max(5, (int) Math.round(normalizedExperienced * 1.6)))
                .hoursFresher(Math.max(7, (int) Math.round(normalizedExperienced * 2.2)))
                .build();
    }

    private int hours(int baseHours, double ratio, int minimum) {
        return Math.max(minimum, (int) Math.round(baseHours * ratio));
    }

    private String taskKey(String taskName) {
        return taskName.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String inferComponentType(String rawType, String moduleName) {
        String source = (rawType == null ? "" : rawType) + " " + (moduleName == null ? "" : moduleName);
        String value = source.toLowerCase();
        if (value.contains("mobile") || value.contains("android") || value.contains("ios") || value.contains("flutter")
                || value.contains("react native")) {
            return "mobile";
        }
        if (value.contains("frontend") || value.contains("ui") || value.contains("client")) {
            return "frontend";
        }
        if (value.contains("auth") || value.contains("identity")) {
            return "auth";
        }
        if (value.contains("gateway") || value.contains("ingress")) {
            return "gateway";
        }
        if (value.contains("database") || value.contains("storage")) {
            return "database";
        }
        if (value.contains("cache") || value.contains("redis")) {
            return "cache";
        }
        if (value.contains("queue") || value.contains("kafka") || value.contains("sqs") || value.contains("rabbit")) {
            return "queue";
        }
        if (value.contains("devops") || value.contains("deployment") || value.contains("pipeline") || value.contains("infra")) {
            return "devops";
        }
        if (value.contains("observability") || value.contains("monitoring") || value.contains("alert")) {
            return "observability";
        }
        return "service";
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

    private List<String> readStringArray(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
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

    private List<WireframeScreen> buildFallbackWireframeScreens(List<Component> components) {
        List<WireframeScreen> screens = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String defaultPlatform = "Web";

        addWireframeScreen(
                screens,
                seen,
                "Authentication & Onboarding",
                defaultPlatform,
                "Register and authenticate users with secure identity flow.",
                "Top-level auth header, onboarding cards, form panel, social login options, legal links, trust indicators.",
                List.of("Sign up form", "Login form", "OTP input", "Password reset", "Terms consent"),
                List.of("Submit credentials", "OTP verification", "Error banner handling", "Session bootstrap"),
                List.of("POST /api/v1/auth/register", "POST /api/v1/auth/login", "POST /api/v1/auth/refresh")
        );
        addWireframeScreen(
                screens,
                seen,
                "Primary Dashboard",
                defaultPlatform,
                "Display the product's core business KPIs and actionable modules.",
                "Global navigation shell, KPI cards row, primary activity feed, contextual action panel, alert rail.",
                List.of("KPI cards", "Activity timeline", "Quick actions", "Status chips", "Search bar"),
                List.of("Filter dashboard widgets", "Drill into module details", "Trigger quick actions"),
                List.of("GET /api/v1/feed", "GET /api/v1/recommendations", "GET /api/v1/notifications")
        );
        addWireframeScreen(
                screens,
                seen,
                "Entity Listing & Search",
                defaultPlatform,
                "Search, filter, and paginate all core business entities.",
                "Faceted sidebar filters, searchable table/list, pagination footer, bulk-action toolbar.",
                List.of("Search input", "Filter panel", "Paginated list", "Sort controls", "Bulk actions"),
                List.of("Search by keyword", "Apply multi-filter", "Batch operations"),
                List.of("GET /api/v1/search", "GET /api/v1/resources", "PATCH /api/v1/resources/{resourceId}")
        );
        addWireframeScreen(
                screens,
                seen,
                "Entity Details & Edit",
                defaultPlatform,
                "Read and modify complete entity details with audit-safe updates.",
                "Detail summary header, tabbed information sections, editable forms, history/audit sidebar.",
                List.of("Detail cards", "Edit form", "Audit timeline", "Attachment panel", "Validation messages"),
                List.of("Inline edit", "Save draft", "Submit update", "Revert changes"),
                List.of("GET /api/v1/resources/{resourceId}", "PUT /api/v1/resources/{resourceId}", "GET /api/v1/admin/audit-logs")
        );
        addWireframeScreen(
                screens,
                seen,
                "Notifications & Inbox",
                defaultPlatform,
                "Track system/user notifications and resolve pending actions.",
                "Notification stream, category tabs, unread counters, action drawer.",
                List.of("Notification cards", "Unread badges", "Action buttons", "Filter tabs"),
                List.of("Mark as read", "Snooze notification", "Open linked workflow"),
                List.of("GET /api/v1/notifications", "PATCH /api/v1/notifications/{notificationId}/read")
        );
        addWireframeScreen(
                screens,
                seen,
                "Admin & Operations",
                defaultPlatform,
                "Operate production workloads, jobs, and platform health from one place.",
                "Ops metric tiles, scheduled jobs table, incident panel, feature flag controls.",
                List.of("Job queue table", "Metrics tiles", "Feature toggle controls", "Incident timeline"),
                List.of("Trigger admin job", "Pause/resume jobs", "Inspect incidents"),
                List.of("POST /api/v1/admin/jobs", "GET /api/v1/admin/jobs", "GET /api/v1/metrics")
        );

        if (components != null) {
            List<Component> ordered = new ArrayList<>(components);
            ordered.sort(Comparator.comparing(c -> positiveOr(c.getBuildOrder(), Integer.MAX_VALUE)));
            for (Component component : ordered) {
                if (component == null || component.getName() == null || component.getName().isBlank()) {
                    continue;
                }
                if (screens.size() >= 12) {
                    break;
                }
                String platform = inferComponentType(component.getType(), component.getName()).equals("mobile")
                        ? "Mobile"
                        : defaultPlatform;
                addWireframeScreen(
                        screens,
                        seen,
                        component.getName() + " Module",
                        platform,
                        component.getResponsibility() == null || component.getResponsibility().isBlank()
                                ? "Dedicated screen for " + component.getName() + " workflows."
                                : component.getResponsibility(),
                        "Primary workspace with module summary, action panel, details region, and contextual insights.",
                        List.of("Header", "Summary cards", "Primary list/grid", "Action buttons", "Status panel"),
                        List.of("Create item", "Update item", "Inspect dependency state"),
                        List.of("GET /api/v1/" + slugify(component.getName()), "POST /api/v1/" + slugify(component.getName()))
                );
            }
        }
        return screens;
    }

    private void addWireframeScreen(
            List<WireframeScreen> screens,
            Set<String> seen,
            String screenName,
            String platform,
            String purpose,
            String layoutDescription,
            List<String> uiComponents,
            List<String> interactions,
            List<String> apiBindings
    ) {
        String key = screenName == null ? "" : screenName.trim().toLowerCase();
        if (key.isBlank() || seen.contains(key)) {
            return;
        }
        seen.add(key);
        screens.add(WireframeScreen.builder()
                .screenName(screenName)
                .platform(platform)
                .purpose(purpose)
                .layoutDescription(layoutDescription)
                .uiComponents(uiComponents)
                .interactions(interactions)
                .apiBindings(apiBindings)
                .build());
    }

    private DesignStageResult executeSowStageWithFallback(UUID designId, DesignRequestDTO request) {
        final String stageName = "SOW";
        final int progress = 6;
        designGenerationPublisher.publishStageStarted(designId.toString(), stageName, progress);
        log.info("DesignId={} stage={} started", designId, stageName);
        try {
            DesignStageResult result = aiStageService.generateSow(request);
            persistSowSnapshot(designId, request, result.getContent());
            designGenerationPublisher.publishStageCompleted(designId.toString(), stageName, progress, result.getContent());
            log.info("DesignId={} stage={} completed", designId, stageName);
            return result;
        } catch (Exception ex) {
            log.warn("DesignId={} stage={} failed. Falling back to deterministic SOW.", designId, stageName, ex);
            designGenerationPublisher.publishStageFailed(designId.toString(), stageName, ex.getMessage());
            Map<String, Object> fallbackSow = Map.of(
                    "sow",
                    Map.of(
                            "project_summary", "This scope of work defines delivery for a production-ready architecture and implementation plan. "
                                    + "It includes requirement clarification, component design, API specification, task-level execution planning, "
                                    + "wireframe definition, deployment alignment, and operational readiness expectations. The SOW is structured for "
                                    + "engineering execution with measurable outcomes, phased milestones, and clear in-scope/out-of-scope boundaries.",
                            "business_objectives", List.of(
                                    "Deliver a scalable architecture blueprint aligned with growth targets and non-functional constraints.",
                                    "Provide implementation-grade APIs and module contracts to accelerate engineering execution.",
                                    "Reduce delivery ambiguity by mapping requirements to concrete components and milestones.",
                                    "Establish operational readiness with observability, reliability, and deployment guardrails."
                            ),
                            "in_scope", List.of(
                                    "Requirement decomposition into platform modules and engineering workstreams.",
                                    "High-level and low-level architecture for core and supporting services.",
                                    "API contract inventory covering business, admin, and operational flows.",
                                    "Database, cache, queue, and storage strategy for expected scale profile.",
                                    "Data flow coverage for synchronous and asynchronous workflows.",
                                    "Task-level execution plan with role-based hour estimates.",
                                    "Wireframe-level UX blueprint for key user and operations flows.",
                                    "Deployment and runtime architecture alignment for selected infra stack.",
                                    "Resilience, scalability, and monitoring strategy recommendations.",
                                    "Documentation and handoff artifacts for implementation teams."
                            ),
                            "out_of_scope", List.of(
                                    "Post-go-live feature expansion beyond approved requirement baseline.",
                                    "Procurement and commercial negotiation with third-party vendors.",
                                    "Legal, compliance, and policy approvals outside technical implementation scope.",
                                    "Long-term managed operations and on-call staffing agreements.",
                                    "End-user training programs and customer support playbook creation.",
                                    "Marketing, GTM strategy, or business process consulting deliverables."
                            ),
                            "dependencies", List.of(
                                    "Finalized and prioritized functional requirements approved by product stakeholders.",
                                    "Infrastructure access and environment provisioning for development and validation.",
                                    "Availability of domain SMEs for requirement clarification during design cycles.",
                                    "Decisions on security posture, auth provider, and compliance baseline."
                            ),
                            "deliverables", List.of(
                                    "Structured system design document covering architecture, components, and flows.",
                                    "Detailed scope of work with phase-wise engineering objectives and boundaries.",
                                    "Comprehensive API inventory with method/path and purpose mapping.",
                                    "Module-level breakdown with implementation sequencing.",
                                    "Low-level design references for key components and interfaces.",
                                    "Task-level work plan with experienced/mid/fresher effort estimates.",
                                    "Wireframe blueprint for core product and operational screens.",
                                    "Scalability and reliability strategy recommendations.",
                                    "Failure handling and degradation strategy notes.",
                                    "Export-ready artifacts for PDF and CSV handoff."
                            ),
                            "milestones", List.of(
                                    "Phase 1 - Discovery closure (Owner: Product + Architect) completed when requirements and constraints are baseline-approved.",
                                    "Phase 2 - SOW finalization (Owner: Architect) completed when scope, assumptions, risks, and acceptance criteria are signed off.",
                                    "Phase 3 - HLD completion (Owner: Architecture team) completed when target architecture and core component map are frozen.",
                                    "Phase 4 - API and data model closure (Owner: Backend lead) completed when API inventory and schema strategy are approved.",
                                    "Phase 5 - LLD completion (Owner: Engineering leads) completed when component internals and integration contracts are finalized.",
                                    "Phase 6 - Task plan approval (Owner: Delivery manager) completed when effort estimates and execution sequence are validated.",
                                    "Phase 7 - Wireframe alignment (Owner: Product + UX) completed when key journey screens are reviewed and approved.",
                                    "Phase 8 - Handoff readiness (Owner: Program owner) completed when documentation and execution artifacts are published."
                            ),
                            "acceptance_criteria", List.of(
                                    "All prioritized functional requirements are mapped to at least one implementation component.",
                                    "Core non-functional requirements are explicitly addressed in architecture decisions.",
                                    "API inventory includes required business, admin, and operational interfaces.",
                                    "Task breakdown includes module-wise detailed tasks and role-specific estimates.",
                                    "Wireframe output covers critical end-user and administrative workflows.",
                                    "Architecture includes resilience and scaling strategy for expected demand.",
                                    "Dependencies and out-of-scope constraints are explicitly documented.",
                                    "Milestones include owner and completion condition per phase.",
                                    "Generated artifacts are exportable and consumable by implementation teams.",
                                    "Design package is sufficient for sprint planning and engineering kickoff."
                            ),
                            "risks", List.of(
                                    "Requirement volatility may expand scope and impact estimates; mitigation: enforce baseline and change-control checkpoints.",
                                    "Third-party integration uncertainty may delay implementation; mitigation: isolate adapters and define fallback contracts.",
                                    "Environment provisioning delays may block validation; mitigation: pre-create infra and define mock/stub plan.",
                                    "Performance assumptions may not hold at scale; mitigation: define load thresholds and early performance tests.",
                                    "Security design gaps may trigger late rework; mitigation: include threat-model review before implementation.",
                                    "Cross-team dependency slippage may affect milestones; mitigation: explicit ownership and dependency tracking board.",
                                    "Data model changes may ripple through APIs; mitigation: version schema and freeze core contracts early.",
                                    "Tooling/stack mismatch with team skills may reduce velocity; mitigation: align stack decisions with team competency."
                            ),
                            "assumptions", List.of(
                                    "Requirement backlog is prioritized and approved before deep implementation planning.",
                                    "Delivery team has access to required repositories, environments, and observability tooling.",
                                    "Target cloud/infrastructure baseline is available for architecture alignment.",
                                    "Security/compliance controls are defined at project start and do not change materially mid-cycle.",
                                    "Core business workflows remain stable during the initial planning window.",
                                    "Cross-functional stakeholders are available for periodic sign-off checkpoints.",
                                    "External dependencies provide stable API contracts or sandbox access.",
                                    "Engineering capacity assumptions remain consistent with current staffing plan."
                            )
                    )
            );
            String fallbackJson = toJsonSafely(fallbackSow, "{\"sow\":{}}");
            DesignStageResult fallback = DesignStageResult.builder()
                    .stageName(stageName)
                    .content(fallbackJson)
                    .createdAt(LocalDateTime.now())
                    .build();
            persistSowSnapshot(designId, request, fallbackJson);
            designGenerationPublisher.publishStageCompleted(designId.toString(), stageName, progress, fallbackJson);
            log.info("DesignId={} stage={} completed with fallback output", designId, stageName);
            return fallback;
        }
    }

    private void persistSowSnapshot(UUID designId, DesignRequestDTO request, String sowJson) {
        try {
            JsonNode requestSnapshot = objectMapper.valueToTree(request);
            SystemDesign design = systemDesignRepository.findById(designId)
                    .orElseGet(() -> SystemDesign.builder()
                            .id(designId)
                            .productName(request.getProductName())
                            .version(
                                    systemDesignRepository.findTopByProductNameOrderByVersionDesc(request.getProductName())
                                            .map(existing -> existing.getVersion() + 1)
                                            .orElse(1)
                            )
                            .requestJson(requestSnapshot)
                            .documentJson(documentMapper.toJsonNode(buildPlaceholderDocument()))
                            .build());

            SystemDesignDocument currentDocument = documentMapper.fromJsonNode(design.getDocumentJson());
            if (currentDocument == null) {
                currentDocument = buildPlaceholderDocument();
            }
            String resolvedSow = resolveSowText(readJson(sowJson));
            if (!resolvedSow.isBlank()) {
                currentDocument.setSow(resolvedSow);
            } else if (sowJson != null && !sowJson.isBlank()) {
                currentDocument.setSow(sowJson);
            }

            design.setProductName(request.getProductName());
            design.setRequestJson(requestSnapshot);
            design.setDocumentJson(documentMapper.toJsonNode(currentDocument));
            systemDesignRepository.save(design);
        } catch (Exception ex) {
            log.warn("Failed to persist SOW snapshot for designId={}", designId, ex);
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
        final int progress = 97;
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

    private DesignStageResult executeWireframeStageWithFallback(
            UUID designId,
            DesignStageResult hld,
            DesignStageResult componentBreakdown,
            DesignStageResult lld
    ) {
        final String stageName = "WIREFRAME";
        final int progress = 100;
        designGenerationPublisher.publishStageStarted(designId.toString(), stageName, progress);
        log.info("DesignId={} stage={} started", designId, stageName);
        try {
            DesignStageResult result = aiStageService.generateWireframe(hld, componentBreakdown, lld);
            designGenerationPublisher.publishStageCompleted(designId.toString(), stageName, progress, result.getContent());
            log.info("DesignId={} stage={} completed", designId, stageName);
            return result;
        } catch (Exception ex) {
            log.warn("DesignId={} stage={} failed. Falling back to deterministic wireframe.", designId, stageName, ex);
            designGenerationPublisher.publishStageFailed(designId.toString(), stageName, ex.getMessage());
            List<WireframeScreen> fallbackScreens = buildFallbackWireframeScreens(extractComponentsForFallback(componentBreakdown));
            String fallbackJson = toJsonSafely(
                    Map.of(
                            "wireframe_summary", "Wireframe generated from module and API design context.",
                            "screens", fallbackScreens
                    ),
                    "{\"wireframe_summary\":\"\",\"screens\":[]}"
            );
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

    private void appendListSection(StringBuilder builder, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(title).append(":\n");
        for (String item : items) {
            builder.append("- ").append(item).append("\n");
        }
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
