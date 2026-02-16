package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignStageResult;
import com.aiarch.systemdesign.dto.document.ApiContract;
import com.aiarch.systemdesign.dto.document.Component;
import com.aiarch.systemdesign.dto.document.ComponentLLD;
import com.aiarch.systemdesign.dto.document.DataFlowScenario;
import com.aiarch.systemdesign.dto.document.DatabaseSchema;
import com.aiarch.systemdesign.dto.document.DiagramMetadata;
import com.aiarch.systemdesign.dto.document.SystemDesignDocument;
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
import java.util.List;
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
            DesignStageResult diagramMetadata = executeStage(
                    designId,
                    "DIAGRAM_METADATA",
                    100,
                    () -> aiStageService.generateDiagramMetadata(hld, lld)
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
                    diagramMetadata.getContent()
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
            String diagramJson
    ) {
        JsonNode hldNode = readJson(hldJson);
        JsonNode componentNode = readJson(componentJson);
        JsonNode lldNode = readJson(lldJson);
        JsonNode dataFlowNode = readJson(dataFlowJson);
        JsonNode scalingNode = readJson(scalingJson);
        JsonNode failureNode = readJson(failureJson);
        JsonNode diagramNode = readJson(diagramJson);

        return SystemDesignDocument.builder()
                .overview(getText(hldNode, "overview"))
                .assumptions(readList(hldNode, "assumptions", STRING_LIST_TYPE))
                .capacityEstimation(getText(hldNode, "capacity_estimation"))
                .hld(getText(hldNode, "hld"))
                .components(readList(componentNode, "components", COMPONENT_LIST_TYPE))
                .lld(readList(lldNode, "lld", COMPONENT_LLD_LIST_TYPE))
                .apiContracts(readList(hldNode, "api_contracts", API_CONTRACT_LIST_TYPE))
                .databaseSchemas(readList(hldNode, "database_schemas", DATABASE_SCHEMA_LIST_TYPE))
                .dataFlowScenarios(readList(dataFlowNode, "data_flow_scenarios", DATA_FLOW_LIST_TYPE))
                .scalingStrategy(getText(scalingNode, "scaling_strategy"))
                .failureHandling(getText(failureNode, "failure_handling"))
                .tradeoffs(getText(hldNode, "tradeoffs"))
                .diagramMetadata(readObject(diagramNode, DiagramMetadata.class))
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
