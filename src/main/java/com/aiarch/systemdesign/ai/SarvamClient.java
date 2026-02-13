package com.aiarch.systemdesign.ai;

import com.aiarch.systemdesign.config.SarvamProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class SarvamClient {

    private static final Logger log = LoggerFactory.getLogger(SarvamClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final WebClient.Builder webClientBuilder;
    private final SarvamProperties sarvamProperties;
    private final ObjectMapper objectMapper;

    public String generateCompletion(String prompt) {
        if (sarvamProperties.getApiKey() == null || sarvamProperties.getApiKey().isBlank()) {
            throw new IllegalStateException("Sarvam API key is not configured");
        }

        Map<String, Object> requestBody = buildRequestBody(prompt);

        WebClient webClient = webClientBuilder
                .baseUrl(sarvamProperties.getBaseUrl())
                .build();

        log.info("Sending request to Sarvam model={}", sarvamProperties.getModel());
        log.debug("Sarvam prompt: {}", prompt);

        try {
            String response = webClient.post()
                    .uri("")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + sarvamProperties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .flatMap(errorBody -> {
                                        log.error("Sarvam API error: status={}, body={}",
                                                clientResponse.statusCode(), errorBody);
                                        return Mono.error(new IllegalStateException(
                                                "Sarvam API request failed with status " + clientResponse.statusCode()
                                        ));
                                    })
                    )
                    .bodyToMono(String.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();

            log.info("Received response from Sarvam");
            log.debug("Sarvam raw response: {}", response);
            return extractAssistantContent(response);
        } catch (Exception ex) {
            log.error("Failed to call Sarvam API", ex);
            throw new IllegalStateException("Failed to call Sarvam API", ex);
        }
    }

    private String extractAssistantContent(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                if (contentNode.isTextual()) {
                    return normalizeContent(contentNode.asText());
                }
                if (contentNode.isArray() && contentNode.size() > 0) {
                    JsonNode firstTextNode = contentNode.get(0).path("text");
                    if (!firstTextNode.isMissingNode() && firstTextNode.isTextual()) {
                        return normalizeContent(firstTextNode.asText());
                    }
                }
            }
            return normalizeContent(rawResponse);
        } catch (Exception ex) {
            log.warn("Unable to parse Sarvam response envelope. Falling back to raw response body.", ex);
            return normalizeContent(rawResponse);
        }
    }

    private String normalizeContent(String content) {
        String cleaned = content.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned
                    .replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```\\s*$", "")
                    .trim();
        }
        return cleaned;
    }

    private Map<String, Object> buildRequestBody(String prompt) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", sarvamProperties.getModel());
        requestBody.put("messages", List.of(
                Map.of(
                        "role", "user",
                        "content", prompt
                )
        ));
        requestBody.put("temperature", 0.2);
        requestBody.put("stream", false);
        if (sarvamProperties.getMaxTokens() != null && sarvamProperties.getMaxTokens() > 0) {
            requestBody.put("max_tokens", sarvamProperties.getMaxTokens());
        }
        return requestBody;
    }
}
