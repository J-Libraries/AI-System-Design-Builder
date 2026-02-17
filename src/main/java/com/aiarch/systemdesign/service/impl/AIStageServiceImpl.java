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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        List<String> parseCandidates = buildParseCandidates(rawResponse);
        JsonProcessingException lastException = null;

        for (String candidate : parseCandidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                return objectMapper.readTree(candidate);
            } catch (JsonProcessingException parseException) {
                lastException = parseException;
                String sanitized = sanitizeInvalidEscapesInJsonStrings(candidate);
                if (!sanitized.equals(candidate)) {
                    try {
                        return objectMapper.readTree(sanitized);
                    } catch (JsonProcessingException sanitizedException) {
                        lastException = sanitizedException;
                    }
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new JsonProcessingException("No JSON candidate available for parsing") { };
    }

    private List<String> buildParseCandidates(String rawResponse) {
        LinkedHashSet<String> uniqueCandidates = new LinkedHashSet<>();
        if (rawResponse == null) {
            return List.of();
        }

        String trimmed = rawResponse.trim();
        if (!trimmed.isBlank()) {
            uniqueCandidates.add(trimmed);
        }

        String withoutCodeFence = stripMarkdownCodeFence(trimmed);
        if (!withoutCodeFence.isBlank()) {
            uniqueCandidates.add(withoutCodeFence);
        }

        extractJsonObjectCandidate(trimmed).ifPresent(uniqueCandidates::add);
        extractJsonObjectCandidate(withoutCodeFence).ifPresent(uniqueCandidates::add);

        List<String> ordered = new ArrayList<>(uniqueCandidates);
        List<String> withSanitizedEscapes = new ArrayList<>();
        for (String candidate : ordered) {
            String sanitized = sanitizeInvalidEscapesInJsonStrings(candidate);
            if (!sanitized.equals(candidate)) {
                withSanitizedEscapes.add(sanitized);
            }
        }
        ordered.addAll(withSanitizedEscapes);
        return ordered;
    }

    private String stripMarkdownCodeFence(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstLineBreak = trimmed.indexOf('\n');
        if (firstLineBreak < 0) {
            return trimmed;
        }

        String inner = trimmed.substring(firstLineBreak + 1);
        int endingFence = inner.lastIndexOf("```");
        if (endingFence >= 0) {
            inner = inner.substring(0, endingFence);
        }
        return inner.trim();
    }

    private String sanitizeInvalidEscapesInJsonStrings(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }

        StringBuilder builder = new StringBuilder(value.length());
        boolean inString = false;
        boolean pendingEscape = false;

        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);

            if (!inString) {
                builder.append(current);
                if (current == '"') {
                    inString = true;
                }
                continue;
            }

            if (pendingEscape) {
                if (current == 'u') {
                    if (hasValidUnicodeEscape(value, index + 1)) {
                        builder.append('\\').append('u');
                        builder.append(value, index + 1, index + 5);
                        index += 4;
                    } else {
                        builder.append('u');
                    }
                } else if (isValidJsonEscapeCharacter(current)) {
                    builder.append('\\').append(current);
                } else {
                    // Recover from malformed escapes like \} or \a by removing the backslash.
                    builder.append(current);
                }
                pendingEscape = false;
                continue;
            }

            if (current == '\\') {
                pendingEscape = true;
                continue;
            }

            builder.append(current);
            if (current == '"') {
                inString = false;
            }
        }

        return builder.toString();
    }

    private boolean hasValidUnicodeEscape(String value, int startIndex) {
        if (startIndex + 4 > value.length()) {
            return false;
        }
        for (int index = startIndex; index < startIndex + 4; index++) {
            if (!isHexDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean isHexDigit(char value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private boolean isValidJsonEscapeCharacter(char value) {
        return value == '"'
                || value == '\\'
                || value == '/'
                || value == 'b'
                || value == 'f'
                || value == 'n'
                || value == 'r'
                || value == 't';
    }

    private boolean shouldEscapeBackslashPreview(char value) {
        return value == '"'
                || value == '\\'
                || value == '/'
                || value == 'b'
                || value == 'f'
                || value == 'n'
                || value == 'r'
                || value == 't'
                || value == 'u';
    }

    private String sanitizeForRetryContext(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = raw.replace("\\", "\\\\");
        StringBuilder builder = new StringBuilder(sanitized.length());
        for (int index = 0; index < sanitized.length(); index++) {
            char current = sanitized.charAt(index);
            if (current == '\\' && index + 1 < sanitized.length()) {
                char next = sanitized.charAt(index + 1);
                if (!shouldEscapeBackslashPreview(next)) {
                    builder.append("\\\\");
                    continue;
                }
            }
            builder.append(current);
        }
        return builder.toString();
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
        boundedPrevious = sanitizeForRetryContext(boundedPrevious);
        return initialPrompt
                + promptTemplateService.invalidJsonRetrySuffix()
                + "\nJSON escaping rules: only use valid escapes (\\\\, \\\", \\/, \\b, \\f, \\n, \\r, \\t, \\uXXXX). Never use invalid escapes like \\}."
                + "\n\nStage: " + stageName
                + "\nPrevious invalid output (truncated):\n"
                + boundedPrevious
                + "\n\nRegenerate from scratch as compact valid JSON in one object. Do not leave any field incomplete.";
    }
}
