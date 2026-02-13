package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignStageResult;
import com.aiarch.systemdesign.dto.FinalDesignResponse;
import com.aiarch.systemdesign.model.SystemDesign;
import com.aiarch.systemdesign.repository.SystemDesignRepository;
import com.aiarch.systemdesign.service.AIStageService;
import com.aiarch.systemdesign.service.DesignGenerationPublisher;
import com.aiarch.systemdesign.service.DesignOrchestratorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private final AIStageService aiStageService;
    private final SystemDesignRepository systemDesignRepository;
    private final ObjectMapper objectMapper;
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
            DesignStageResult hld = executeStage(
                    designId,
                    "HLD",
                    10,
                    () -> aiStageService.generateHLD(request)
            );
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

            FinalDesignResponse response = FinalDesignResponse.builder()
                    .hld(readJson(hld.getContent()))
                    .lld(readJson(lld.getContent()))
                    .dataFlow(readJson(dataFlow.getContent()))
                    .scalingStrategy(readJson(scaling.getContent()))
                    .failureHandling(readJson(failureHandling.getContent()))
                    .diagramMetadata(readJson(diagramMetadata.getContent()))
                    .build();

            int nextVersion = systemDesignRepository.findTopByProductNameOrderByVersionDesc(request.getProductName())
                    .map(existing -> existing.getVersion() + 1)
                    .orElse(1);

            SystemDesign systemDesign = SystemDesign.builder()
                    .id(designId)
                    .productName(request.getProductName())
                    .version(nextVersion)
                    .fullDesignJson(buildPersistedJson(response, componentBreakdown))
                    .build();
            systemDesignRepository.save(systemDesign);

            log.info(
                    "Completed orchestration for designId={} product={} version={}",
                    designId,
                    request.getProductName(),
                    nextVersion
            );
            return CompletableFuture.completedFuture(null);
        } catch (Exception ex) {
            log.error("Design orchestration failed for designId={}", designId, ex);
            designGenerationPublisher.publishStageFailed(designId.toString(), "ORCHESTRATION", ex.getMessage());
            return CompletableFuture.failedFuture(ex);
        }
    }

    private JsonNode readJson(String rawJson) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse generated stage JSON", ex);
        }
    }

    private JsonNode buildPersistedJson(FinalDesignResponse response, DesignStageResult componentBreakdown) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("hld", response.getHld());
        root.set("component_breakdown", readJson(componentBreakdown.getContent()));
        root.set("lld", response.getLld());
        root.set("data_flow", response.getDataFlow());
        root.set("scaling_strategy", response.getScalingStrategy());
        root.set("failure_handling", response.getFailureHandling());
        root.set("diagram_metadata", response.getDiagramMetadata());
        return root;
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
