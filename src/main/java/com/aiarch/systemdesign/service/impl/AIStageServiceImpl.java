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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private static final int MAX_SCREEN_HTML_REPAIR_ATTEMPTS = 2;
    private static final Set<String> WIREFRAME_SCREEN_REQUIRED_FIELDS = Set.of(
            "screen_name",
            "route_id",
            "platform",
            "purpose",
            "layout_description",
            "ui_components",
            "interactions",
            "api_bindings",
            "next_screen_ids",
            "screen_html"
    );

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
        String hldJson = hld.getContent();
        String componentBreakdownJson = componentBreakdown.getContent();
        String lldJson = lld.getContent();

        log.info("Starting AI stage=WIREFRAME with recursive per-screen generation pipeline");
        DesignStageResult screenListResult = runStage(
                "WIREFRAME_SCREEN_LIST",
                promptTemplateService.wireframeScreenListPrompt(hldJson, componentBreakdownJson, lldJson),
                Set.of("wireframe_summary", "screens")
        );

        JsonNode screenListNode = parseAndValidate(
                "WIREFRAME_SCREEN_LIST_RESULT",
                screenListResult.getContent(),
                Set.of("wireframe_summary", "screens")
        );
        JsonNode rawScreens = screenListNode.path("screens");
        if (!rawScreens.isArray() || rawScreens.isEmpty()) {
            throw new InvalidAiResponseException("Wireframe screen list is empty");
        }

        ArrayNode generatedScreens = objectMapper.createArrayNode();
        for (int index = 0; index < rawScreens.size(); index++) {
            JsonNode screenSpec = normalizeScreenSpec(rawScreens.get(index), index);
            String stageName = "WIREFRAME_SCREEN_HTML_" + (index + 1);
            log.info("Generating wireframe HTML for screen index={} routeId={}", index + 1, screenSpec.path("route_id").asText());

            DesignStageResult screenResult = runStage(
                    stageName,
                    promptTemplateService.wireframeScreenHtmlPrompt(
                            hldJson,
                            componentBreakdownJson,
                            lldJson,
                            rawScreens.toString(),
                            screenSpec.toString()
                    ),
                    WIREFRAME_SCREEN_REQUIRED_FIELDS
            );

            JsonNode generatedScreen = parseAndValidate(
                    stageName + "_RESULT",
                    screenResult.getContent(),
                    WIREFRAME_SCREEN_REQUIRED_FIELDS
            );
            JsonNode validatedScreen = validateAndRepairScreenHtml(screenSpec, generatedScreen, index);
            generatedScreens.add(validatedScreen);
        }

        ArrayNode connectedScreens = connectWireframeScreens(generatedScreens);
        ObjectNode finalPayload = objectMapper.createObjectNode();
        finalPayload.put(
                "wireframe_summary",
                safeText(screenListNode.path("wireframe_summary"), "Interactive wireframe generated via recursive screen pipeline.")
        );
        finalPayload.set("screens", connectedScreens);

        return DesignStageResult.builder()
                .stageName("WIREFRAME")
                .content(finalPayload.toString())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private JsonNode validateAndRepairScreenHtml(JsonNode screenSpec, JsonNode generatedScreen, int index) {
        JsonNode currentScreen = coerceScreenNode(screenSpec, generatedScreen, index);

        for (int attempt = 1; attempt <= MAX_SCREEN_HTML_REPAIR_ATTEMPTS + 1; attempt++) {
            List<String> validationErrors = validateScreenNode(currentScreen);
            if (validationErrors.isEmpty()) {
                if (attempt > 1) {
                    log.info("Wireframe screen index={} validated after repair attempt={}", index + 1, attempt - 1);
                }
                return currentScreen;
            }

            if (attempt > MAX_SCREEN_HTML_REPAIR_ATTEMPTS) {
                throw new InvalidAiResponseException(
                        "Wireframe screen "
                                + safeText(currentScreen.path("route_id"), "screen-" + (index + 1))
                                + " failed HTML validation after retries: "
                                + String.join("; ", validationErrors)
                );
            }

            String stageName = "WIREFRAME_SCREEN_REPAIR_" + (index + 1) + "_ATTEMPT_" + attempt;
            log.warn(
                    "Wireframe screen index={} failed validation on attempt={}: {}. Triggering repair.",
                    index + 1,
                    attempt,
                    String.join("; ", validationErrors)
            );
            DesignStageResult repaired = runStage(
                    stageName,
                    promptTemplateService.wireframeScreenRepairPrompt(
                            screenSpec.toString(),
                            currentScreen.toString(),
                            String.join("; ", validationErrors)
                    ),
                    WIREFRAME_SCREEN_REQUIRED_FIELDS
            );
            JsonNode repairedNode = parseAndValidate(stageName + "_RESULT", repaired.getContent(), WIREFRAME_SCREEN_REQUIRED_FIELDS);
            currentScreen = coerceScreenNode(screenSpec, repairedNode, index);
        }

        throw new InvalidAiResponseException("Unexpected validation flow for wireframe screen index " + (index + 1));
    }

    private List<String> validateScreenNode(JsonNode screenNode) {
        List<String> errors = new ArrayList<>();

        String routeId = normalizeRouteId(screenNode.path("route_id").asText(""));
        if (routeId.isBlank()) {
            errors.add("route_id is missing");
        }

        String html = safeText(screenNode.path("screen_html"), "");
        if (html.isBlank()) {
            errors.add("screen_html is empty");
        } else {
            String normalizedHtml = html.toLowerCase(Locale.ROOT);
            if (!normalizedHtml.contains("<") || !normalizedHtml.contains(">")) {
                errors.add("screen_html is not valid HTML content");
            }
            if (normalizedHtml.contains("```")) {
                errors.add("screen_html must not contain markdown fences");
            }
            if (!(normalizedHtml.contains("<main")
                    || normalizedHtml.contains("<section")
                    || normalizedHtml.contains("<div")
                    || normalizedHtml.contains("<article"))) {
                errors.add("screen_html must include semantic layout tags");
            }
        }

        JsonNode nextScreens = screenNode.path("next_screen_ids");
        if (nextScreens.isArray() && !nextScreens.isEmpty() && !html.isBlank()) {
            for (JsonNode next : nextScreens) {
                String target = normalizeRouteId(next.asText());
                if (target.isBlank()) {
                    continue;
                }
                String doubleQuoteToken = "data-nav-screen=\"" + target + "\"";
                String singleQuoteToken = "data-nav-screen='" + target + "'";
                if (!html.contains(doubleQuoteToken) && !html.contains(singleQuoteToken)) {
                    errors.add("screen_html missing data-nav-screen mapping for route_id=" + target);
                }
            }
        }

        return errors;
    }

    private JsonNode normalizeScreenSpec(JsonNode rawSpec, int index) {
        ObjectNode node = objectMapper.createObjectNode();
        String defaultName = "Screen " + (index + 1);
        String screenName = safeText(rawSpec.path("screen_name"), defaultName);
        String routeId = normalizeRouteId(safeText(rawSpec.path("route_id"), slugify(screenName)));
        if (routeId.isBlank()) {
            routeId = "screen-" + (index + 1);
        }

        node.put("screen_name", screenName);
        node.put("route_id", routeId);
        node.put("platform", safeText(rawSpec.path("platform"), "Web"));
        node.put("purpose", safeText(rawSpec.path("purpose"), "Primary workflow screen for " + screenName + "."));
        node.put(
                "layout_description",
                safeText(rawSpec.path("layout_description"), "Structured UI with header, main content, and action footer.")
        );
        node.set("ui_components", toStringArrayNode(rawSpec.path("ui_components")));
        node.set("interactions", toStringArrayNode(rawSpec.path("interactions")));
        node.set("api_bindings", toStringArrayNode(rawSpec.path("api_bindings")));
        node.set("next_screen_ids", normalizeRouteArray(rawSpec.path("next_screen_ids")));
        return node;
    }

    private JsonNode coerceScreenNode(JsonNode screenSpec, JsonNode generatedScreen, int index) {
        ObjectNode node = objectMapper.createObjectNode();
        String fallbackName = safeText(screenSpec.path("screen_name"), "Screen " + (index + 1));
        String generatedName = safeText(generatedScreen.path("screen_name"), fallbackName);
        String routeId = normalizeRouteId(
                safeText(generatedScreen.path("route_id"), safeText(screenSpec.path("route_id"), slugify(generatedName)))
        );
        if (routeId.isBlank()) {
            routeId = "screen-" + (index + 1);
        }

        node.put("screen_name", generatedName);
        node.put("route_id", routeId);
        node.put("platform", safeText(generatedScreen.path("platform"), safeText(screenSpec.path("platform"), "Web")));
        node.put("purpose", safeText(generatedScreen.path("purpose"), safeText(screenSpec.path("purpose"), "")));
        node.put(
                "layout_description",
                safeText(
                        generatedScreen.path("layout_description"),
                        safeText(screenSpec.path("layout_description"), "Structured UI with clear visual hierarchy.")
                )
        );
        node.set("ui_components", mergeStringArrays(generatedScreen.path("ui_components"), screenSpec.path("ui_components")));
        node.set("interactions", mergeStringArrays(generatedScreen.path("interactions"), screenSpec.path("interactions")));
        node.set("api_bindings", mergeStringArrays(generatedScreen.path("api_bindings"), screenSpec.path("api_bindings")));
        ArrayNode next = normalizeRouteArray(generatedScreen.path("next_screen_ids"));
        if (next.isEmpty()) {
            next = normalizeRouteArray(screenSpec.path("next_screen_ids"));
        }
        node.set("next_screen_ids", next);

        String html = safeText(generatedScreen.path("screen_html"), "");
        if (html.isBlank()) {
            html = """
                    <div class="wf-app">
                      <header class="wf-header">
                        <h1>%s</h1>
                        <p>%s</p>
                      </header>
                      <main class="wf-main">
                        <section class="wf-card">
                          <h2>Layout</h2>
                          <p>%s</p>
                        </section>
                      </main>
                      <footer class="wf-footer">%s</footer>
                    </div>
                    """.formatted(
                    escapeHtml(generatedName),
                    escapeHtml(safeText(generatedScreen.path("purpose"), "Primary screen")),
                    escapeHtml(safeText(generatedScreen.path("layout_description"), "Detailed layout")),
                    buildNavigationButtons(next)
            );
        }
        node.put("screen_html", html);
        return node;
    }

    private ArrayNode connectWireframeScreens(ArrayNode screens) {
        ArrayNode connected = objectMapper.createArrayNode();
        List<String> routeOrder = new ArrayList<>();
        for (JsonNode screen : screens) {
            routeOrder.add(normalizeRouteId(screen.path("route_id").asText("")));
        }
        Set<String> routeSet = new LinkedHashSet<>(routeOrder);

        for (int index = 0; index < screens.size(); index++) {
            ObjectNode current = (ObjectNode) screens.get(index).deepCopy();
            ArrayNode next = normalizeRouteArray(current.path("next_screen_ids"));
            ArrayNode filteredNext = objectMapper.createArrayNode();
            for (JsonNode node : next) {
                String route = normalizeRouteId(node.asText(""));
                if (!route.isBlank() && routeSet.contains(route)) {
                    filteredNext.add(route);
                }
            }
            if (filteredNext.isEmpty() && index < routeOrder.size() - 1) {
                filteredNext.add(routeOrder.get(index + 1));
            }
            current.set("next_screen_ids", filteredNext);

            String html = safeText(current.path("screen_html"), "");
            current.put("screen_html", ensureNavigationInHtml(html, filteredNext));
            connected.add(current);
        }
        return connected;
    }

    private String ensureNavigationInHtml(String html, ArrayNode nextScreenIds) {
        if (nextScreenIds == null || nextScreenIds.isEmpty()) {
            return html;
        }

        boolean hasAllLinks = true;
        for (JsonNode next : nextScreenIds) {
            String route = next.asText("");
            if (route.isBlank()) {
                continue;
            }
            String doubleQuoteToken = "data-nav-screen=\"" + route + "\"";
            String singleQuoteToken = "data-nav-screen='" + route + "'";
            if (!html.contains(doubleQuoteToken) && !html.contains(singleQuoteToken)) {
                hasAllLinks = false;
                break;
            }
        }
        if (hasAllLinks) {
            return html;
        }

        String buttons = buildNavigationButtons(nextScreenIds);
        if (html.contains("</footer>")) {
            return html.replace("</footer>", buttons + "</footer>");
        }
        if (html.contains("</main>")) {
            return html.replace("</main>", "<footer class=\"wf-footer\">" + buttons + "</footer></main>");
        }
        return html + "<div class=\"wf-footer\">" + buttons + "</div>";
    }

    private String buildNavigationButtons(ArrayNode nextScreenIds) {
        StringBuilder builder = new StringBuilder();
        if (nextScreenIds == null || nextScreenIds.isEmpty()) {
            return "<button type=\"button\" class=\"wf-btn secondary\" disabled>End of flow</button>";
        }
        for (JsonNode next : nextScreenIds) {
            String route = normalizeRouteId(next.asText(""));
            if (route.isBlank()) {
                continue;
            }
            builder.append("<button type=\"button\" class=\"wf-btn\" data-nav-screen=\"")
                    .append(escapeHtml(route))
                    .append("\">Go to ")
                    .append(escapeHtml(route))
                    .append("</button>");
        }
        if (builder.length() == 0) {
            return "<button type=\"button\" class=\"wf-btn secondary\" disabled>End of flow</button>";
        }
        return builder.toString();
    }

    private ArrayNode normalizeRouteArray(JsonNode node) {
        ArrayNode array = objectMapper.createArrayNode();
        if (!node.isArray()) {
            return array;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String normalized = normalizeRouteId(item.asText(""));
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        }
        unique.forEach(array::add);
        return array;
    }

    private ArrayNode mergeStringArrays(JsonNode primary, JsonNode fallback) {
        ArrayNode merged = toStringArrayNode(primary);
        if (!merged.isEmpty()) {
            return merged;
        }
        return toStringArrayNode(fallback);
    }

    private ArrayNode toStringArrayNode(JsonNode node) {
        ArrayNode array = objectMapper.createArrayNode();
        if (!node.isArray()) {
            return array;
        }
        for (JsonNode item : node) {
            String value = safeText(item, "");
            if (!value.isBlank()) {
                array.add(value);
            }
        }
        return array;
    }

    private String normalizeRouteId(String value) {
        String slug = slugify(value);
        return slug.length() > 64 ? slug.substring(0, 64) : slug;
    }

    private String slugify(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "-");
        return normalized.replaceAll("^-|-$", "");
    }

    private String safeText(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
