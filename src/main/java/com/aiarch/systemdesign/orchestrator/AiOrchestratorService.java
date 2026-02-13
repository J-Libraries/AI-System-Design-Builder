package com.aiarch.systemdesign.orchestrator;

import com.aiarch.systemdesign.ai.SarvamClient;
import com.aiarch.systemdesign.dto.ArchitectureResponse;
import com.aiarch.systemdesign.exception.InvalidAiResponseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiarch.systemdesign.model.Project;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestratorService.class);

    private final SarvamClient sarvamClient;
    private final ObjectMapper objectMapper;

    public ArchitectureResponse generateBasicArchitecture(Project project) {
        String prompt = """
                You are a senior system architect.
                Generate a high level architecture in JSON format for:

                Platform: %s
                Users: %s
                RPS: %s
                Region: %s

                Return ONLY valid JSON.
                Do not include explanations outside JSON.
                Follow this exact schema:
                {
                  "architecture_style": "string",
                  "services": [],
                  "databases": [],
                  "caches": [],
                  "queues": [],
                  "edges": [],
                  "hld_explanation": "string"
                }
                """.formatted(
                safe(project.getPlatform()),
                safe(project.getExpectedUsers()),
                safe(project.getExpectedRps()),
                safe(project.getRegion())
        );

        log.info("Generating architecture for project id={}", project.getId());
        log.debug("Generated prompt for project id={}: {}", project.getId(), prompt);
        String firstResponse = sarvamClient.generateCompletion(prompt);
        log.debug("First AI raw response for project id={}: {}", project.getId(), firstResponse);
        try {
            ArchitectureResponse parsed = parseAndValidate(firstResponse);
            log.info("Architecture response validated successfully on first attempt for project id={}", project.getId());
            return parsed;
        } catch (InvalidAiResponseException firstError) {
            log.warn("First AI response invalid for project id={}. Retrying once.", project.getId(), firstError);
        }

        String retryPrompt = prompt + "\n\nYour previous response was invalid JSON. Return only valid JSON.";
        log.info("Retrying architecture generation for project id={}", project.getId());
        String retryResponse = sarvamClient.generateCompletion(retryPrompt);
        log.debug("Retry AI raw response for project id={}: {}", project.getId(), retryResponse);
        try {
            ArchitectureResponse parsed = parseAndValidate(retryResponse);
            log.info("Architecture response validated successfully on retry for project id={}", project.getId());
            return parsed;
        } catch (InvalidAiResponseException secondError) {
            log.error("AI response validation failed after retry for project id={}", project.getId(), secondError);
            throw new InvalidAiResponseException(
                    "AI returned invalid JSON response after retry. Please try again.",
                    secondError
            );
        }
    }

    private String safe(Object value) {
        return value == null ? "N/A" : value.toString();
    }

    private ArchitectureResponse parseAndValidate(String rawResponse) {
        try {
            ArchitectureResponse response = objectMapper.readValue(rawResponse, ArchitectureResponse.class);
            validateRequiredFields(response);
            return response;
        } catch (JsonProcessingException ex) {
            throw new InvalidAiResponseException("Failed to parse AI response as JSON", ex);
        }
    }

    private void validateRequiredFields(ArchitectureResponse response) {
        if (isBlank(response.getArchitectureStyle())) {
            throw new InvalidAiResponseException("Missing required field: architecture_style");
        }
        if (response.getServices() == null) {
            throw new InvalidAiResponseException("Missing required field: services");
        }
        if (response.getDatabases() == null) {
            throw new InvalidAiResponseException("Missing required field: databases");
        }
        if (response.getCaches() == null) {
            throw new InvalidAiResponseException("Missing required field: caches");
        }
        if (response.getQueues() == null) {
            throw new InvalidAiResponseException("Missing required field: queues");
        }
        if (response.getEdges() == null) {
            throw new InvalidAiResponseException("Missing required field: edges");
        }
        if (isBlank(response.getHldExplanation())) {
            throw new InvalidAiResponseException("Missing required field: hld_explanation");
        }

        boolean hasInvalidService = response.getServices().stream().anyMatch(service ->
                isBlank(service.getName()) || isBlank(service.getType()) || isBlank(service.getDescription()));
        if (hasInvalidService) {
            throw new InvalidAiResponseException("Each service node must contain name, type, and description");
        }

        boolean hasInvalidDatabase = response.getDatabases().stream().anyMatch(database ->
                isBlank(database.getName()) || isBlank(database.getType()));
        if (hasInvalidDatabase) {
            throw new InvalidAiResponseException("Each database node must contain name and type");
        }

        boolean hasInvalidCache = response.getCaches().stream().anyMatch(cache ->
                isBlank(cache.getName()) || isBlank(cache.getType()));
        if (hasInvalidCache) {
            throw new InvalidAiResponseException("Each cache node must contain name and type");
        }

        boolean hasInvalidQueue = response.getQueues().stream().anyMatch(queue ->
                isBlank(queue.getName()) || isBlank(queue.getType()));
        if (hasInvalidQueue) {
            throw new InvalidAiResponseException("Each queue node must contain name and type");
        }

        boolean hasInvalidEdge = response.getEdges().stream().anyMatch(edge ->
                isBlank(edge.getSource()) || isBlank(edge.getTarget()) || isBlank(edge.getLabel()));
        if (hasInvalidEdge) {
            throw new InvalidAiResponseException("Each edge node must contain source, target, and label");
        }
    }

    private boolean isBlank(String value) {
        return Objects.isNull(value) || value.isBlank();
    }
}
