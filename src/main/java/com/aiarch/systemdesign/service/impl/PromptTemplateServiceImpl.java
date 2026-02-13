package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.service.PromptTemplateService;
import org.springframework.stereotype.Service;

@Service
public class PromptTemplateServiceImpl implements PromptTemplateService {

    @Override
    public String hldPrompt(DesignRequestDTO request) {
        return """
                You are a principal system architect.
                Create a high-level architecture for the following product.
                Product Name: %s
                Functional Requirements: %s
                Non-Functional Requirements: %s
                Expected DAU: %s
                Region: %s
                Scale: %s

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "architecture_style": "string",
                  "services": [{"name":"string","type":"string","description":"string"}],
                  "databases": [{"name":"string","type":"string","purpose":"string"}],
                  "entry_points": [{"name":"string","protocol":"string"}],
                  "assumptions": ["string"]
                }
                """.formatted(
                request.getProductName(),
                request.getFunctionalRequirements(),
                request.getNonFunctionalRequirements(),
                request.getExpectedDAU(),
                request.getRegion(),
                request.getScale()
        );
    }

    @Override
    public String componentBreakdownPrompt(String hldJson) {
        return """
                Use the HLD JSON below as source context and generate a detailed component breakdown.
                HLD_JSON:
                %s

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "components": [
                    {"name":"string","responsibilities":["string"],"depends_on":["string"],"apis":["string"]}
                  ]
                }
                """.formatted(hldJson);
    }

    @Override
    public String lldPrompt(String componentBreakdownJson) {
        return """
                Based on the component breakdown JSON below, produce low level design.
                COMPONENT_BREAKDOWN_JSON:
                %s

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "modules": [
                    {"component":"string","classes":["string"],"contracts":["string"],"storage_patterns":["string"]}
                  ]
                }
                """.formatted(componentBreakdownJson);
    }

    @Override
    public String dataFlowPrompt(String hldJson, String lldJson) {
        return """
                Use both HLD and LLD JSON to generate request/response and async data flow scenarios.
                HLD_JSON:
                %s
                LLD_JSON:
                %s

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "scenarios": [
                    {"name":"string","steps":["string"],"latency_budget_ms":"number"}
                  ]
                }
                """.formatted(hldJson, lldJson);
    }

    @Override
    public String scalingStrategyPrompt(String hldJson) {
        return """
                Use the HLD JSON and produce scaling strategy.
                HLD_JSON:
                %s

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "horizontal_scaling": ["string"],
                  "vertical_scaling": ["string"],
                  "partitioning_strategy": ["string"],
                  "capacity_checkpoints": ["string"]
                }
                """.formatted(hldJson);
    }

    @Override
    public String failureHandlingPrompt(String hldJson) {
        return """
                Use the HLD JSON and produce failure handling strategy.
                HLD_JSON:
                %s

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema:
                {
                  "failure_modes": [
                    {"failure":"string","detection":"string","mitigation":"string","fallback":"string"}
                  ],
                  "resilience_patterns": ["string"]
                }
                """.formatted(hldJson);
    }

    @Override
    public String diagramMetadataPrompt(String hldJson, String lldJson) {
        return """
                Produce diagram metadata from HLD and LLD JSON.
                HLD_JSON:
                %s
                LLD_JSON:
                %s

                Return ONLY valid JSON. No markdown. No comments. No additional text.
                Follow this exact schema exactly:
                {
                  "nodes": [
                    { "id": "api-gateway", "type": "gateway" }
                  ],
                  "edges": [
                    { "from": "api-gateway", "to": "user-service", "label": "REST" }
                  ]
                }
                """.formatted(hldJson, lldJson);
    }

    @Override
    public String invalidJsonRetrySuffix() {
        return """
                
                Your previous response was invalid JSON.
                Return only valid JSON that matches the schema exactly.
                """;
    }
}
