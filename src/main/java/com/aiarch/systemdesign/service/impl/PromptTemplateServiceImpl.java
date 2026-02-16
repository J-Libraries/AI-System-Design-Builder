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
                  "overview": "string",
                  "assumptions": ["string"],
                  "capacity_estimation": "string",
                  "hld": "string",
                  "api_contracts": [
                    {
                      "name": "string",
                      "method": "string",
                      "path": "string",
                      "request_schema": "string",
                      "response_schema": "string",
                      "error_codes": ["string"]
                    }
                  ],
                  "database_schemas": [
                    {
                      "entity_name": "string",
                      "fields": ["string"],
                      "indexes": ["string"]
                    }
                  ],
                  "tradeoffs": "string"
                }
                """.formatted(
                request.getProductName(),
                request.getFunctionalRequirements(),
                request.getNonFunctionalRequirements() == null || request.getNonFunctionalRequirements().isEmpty()
                        ? "Not specified"
                        : request.getNonFunctionalRequirements(),
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
                    {"name":"string","type":"string","responsibility":"string","dependencies":["string"]}
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
                  "lld": [
                    {
                      "component_name":"string",
                      "module_description":"string",
                      "classes":["string"],
                      "interfaces":["string"],
                      "sequence":["string"]
                    }
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
                  "data_flow_scenarios": [
                    {"name":"string","trigger":"string","steps":["string"],"expected_outcome":"string"}
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
                  "scaling_strategy": "string"
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
                  "failure_handling": "string"
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
