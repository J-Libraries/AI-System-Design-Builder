package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.ai.SarvamClient;
import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignStageResult;
import com.aiarch.systemdesign.exception.InvalidAiResponseException;
import com.aiarch.systemdesign.service.AIStageService;
import com.aiarch.systemdesign.service.PromptTemplateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AIStageServiceImpl implements AIStageService {

    private static final Logger log = LoggerFactory.getLogger(AIStageServiceImpl.class);
    private static final int MAX_INVALID_JSON_RETRIES = 2;
    private static final int MAX_RETRY_CONTEXT_CHARS = 3000;

    private final SarvamClient sarvamClient;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    @Override
    public DesignStageResult generateSow(DesignRequestDTO request) {
        return runStage(
                "SOW",
                promptTemplateService.sowPrompt(request),
                Set.of("sow")
        );
    }

    @Override
    public DesignStageResult generateHLD(DesignRequestDTO request) {
        return runStage(
                "HLD",
                promptTemplateService.hldPrompt(request),
                Set.of(
                        "overview",
                        "assumptions",
                        "capacity_estimation",
                        "hld",
                        "api_contracts",
                        "database_schemas",
                        "backend_architecture",
                        "software_architecture",
                        "devops_strategy",
                        "docker_strategy",
                        "tradeoffs"
                )
        );
    }

    @Override
    public DesignStageResult generateComponentBreakdown(DesignStageResult hld) {
        return runStage(
                "COMPONENT_BREAKDOWN",
                promptTemplateService.componentBreakdownPrompt(hld.getContent()),
                Set.of("components")
        );
    }

    @Override
    public DesignStageResult generateLLD(DesignStageResult componentBreakdown) {
        return runStage(
                "LLD",
                promptTemplateService.lldPrompt(componentBreakdown.getContent()),
                Set.of("lld")
        );
    }

    @Override
    public DesignStageResult generateDataFlow(DesignStageResult hld, DesignStageResult lld) {
        return runStage(
                "DATA_FLOW",
                promptTemplateService.dataFlowPrompt(hld.getContent(), lld.getContent()),
                Set.of("data_flow_scenarios")
        );
    }

    @Override
    public DesignStageResult generateScalingStrategy(DesignStageResult hld) {
        return runStage(
                "SCALING_STRATEGY",
                promptTemplateService.scalingStrategyPrompt(hld.getContent()),
                Set.of("scaling_strategy")
        );
    }

    @Override
    public DesignStageResult generateFailureHandling(DesignStageResult hld) {
        return runStage(
                "FAILURE_HANDLING",
                promptTemplateService.failureHandlingPrompt(hld.getContent()),
                Set.of("failure_handling")
        );
    }

    @Override
    public DesignStageResult generateDiagramMetadata(DesignStageResult hld, DesignStageResult lld) {
        return runStage(
                "DIAGRAM_METADATA",
                promptTemplateService.diagramMetadataPrompt(hld.getContent(), lld.getContent()),
                Set.of("nodes", "edges")
        );
    }

    @Override
    public DesignStageResult generateTaskBreakdown(
            DesignStageResult hld,
            DesignStageResult componentBreakdown,
            DesignStageResult lld
    ) {
        return runStage(
                "TASK_BREAKDOWN",
                promptTemplateService.taskBreakdownPrompt(
                        hld.getContent(),
                        componentBreakdown.getContent(),
                        lld.getContent()
                ),
                Set.of("task_breakdown")
        );
    }

    @Override
    public DesignStageResult generateWireframe(
            DesignStageResult hld,
            DesignStageResult componentBreakdown,
            DesignStageResult lld
    ) {
        return runStage(
                "WIREFRAME",
                promptTemplateService.wireframePrompt(
                        hld.getContent(),
                        componentBreakdown.getContent(),
                        lld.getContent()
                ),
                Set.of("wireframe_summary", "screens")
        );
    }

    private DesignStageResult runStage(String stageName, String initialPrompt, Set<String> requiredFields) {
        log.info("Starting AI stage={}", stageName);
        String prompt = initialPrompt;
        String lastInvalidResponse = "";

        for (int attempt = 1; attempt <= MAX_INVALID_JSON_RETRIES + 1; attempt++) {
            String rawResponse = sarvamClient.generateCompletion(prompt);
            try {
                JsonNode validated = parseAndValidate(stageName, rawResponse, requiredFields);
                DesignStageResult result = DesignStageResult.builder()
                        .stageName(stageName)
                        .content(validated.toString())
                        .createdAt(LocalDateTime.now())
                        .build();
                log.info("AI stage={} succeeded on attempt={}", stageName, attempt);
                return result;
            } catch (InvalidAiResponseException ex) {
                if (attempt <= MAX_INVALID_JSON_RETRIES) {
                    lastInvalidResponse = rawResponse;
                    log.warn(
                            "AI stage={} returned invalid JSON on attempt={}. Retrying.",
                            stageName,
                            attempt,
                            ex
                    );
                    prompt = buildRetryPrompt(stageName, initialPrompt, lastInvalidResponse);
                } else {
                    log.error("AI stage={} failed after max retries", stageName, ex);
                    throw new InvalidAiResponseException(
                            "Invalid JSON received for stage " + stageName + " after retries",
                            ex
                    );
                }
            }
        }

        throw new InvalidAiResponseException("Unexpected orchestration flow for stage " + stageName);
    }

    private JsonNode parseAndValidate(String stageName, String rawResponse, Set<String> requiredFields) {
        try {
            JsonNode node = parseJsonWithRecovery(rawResponse);
            if (!node.isObject()) {
                throw new InvalidAiResponseException("Stage " + stageName + " response must be a JSON object");
            }
            for (String field : requiredFields) {
                if (!node.has(field) || node.get(field).isNull()) {
                    throw new InvalidAiResponseException(
                            "Stage " + stageName + " is missing required field: " + field
                    );
                }
            }
            return node;
        } catch (JsonProcessingException ex) {
            throw new InvalidAiResponseException("Stage " + stageName + " returned invalid JSON", ex);
        }
    }

    private JsonNode parseJsonWithRecovery(String rawResponse) throws JsonProcessingException {
        try {
            return objectMapper.readTree(rawResponse);
        } catch (JsonProcessingException directParseException) {
            Optional<String> candidate = extractJsonObjectCandidate(rawResponse);
            if (candidate.isPresent()) {
                return objectMapper.readTree(candidate.get());
            }
            throw directParseException;
        }
    }

    private Optional<String> extractJsonObjectCandidate(String value) {
        int firstBrace = value.indexOf('{');
        int lastBrace = value.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return Optional.of(value.substring(firstBrace, lastBrace + 1));
        }
        return Optional.empty();
    }

    private String buildRetryPrompt(String stageName, String initialPrompt, String previousResponse) {
        String boundedPrevious = previousResponse == null ? "" : previousResponse;
        if (boundedPrevious.length() > MAX_RETRY_CONTEXT_CHARS) {
            boundedPrevious = boundedPrevious.substring(0, MAX_RETRY_CONTEXT_CHARS);
        }
        return initialPrompt
                + promptTemplateService.invalidJsonRetrySuffix()
                + "\n\nStage: " + stageName
                + "\nPrevious invalid output (truncated):\n"
                + boundedPrevious
                + "\n\nRegenerate from scratch as compact valid JSON in one object. Do not leave any field incomplete.";
    }
}
