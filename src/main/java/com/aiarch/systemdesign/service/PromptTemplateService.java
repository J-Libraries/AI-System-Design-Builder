package com.aiarch.systemdesign.service;

import com.aiarch.systemdesign.dto.DesignRequestDTO;

public interface PromptTemplateService {

    String sowPrompt(DesignRequestDTO request);

    String hldPrompt(DesignRequestDTO request);

    String componentBreakdownPrompt(String hldJson);

    String lldPrompt(String componentBreakdownJson);

    String dataFlowPrompt(String hldJson, String lldJson);

    String scalingStrategyPrompt(String hldJson);

    String failureHandlingPrompt(String hldJson);

    String diagramMetadataPrompt(String hldJson, String lldJson);

    String taskBreakdownPrompt(String hldJson, String componentBreakdownJson, String lldJson);

    String wireframePrompt(String hldJson, String componentBreakdownJson, String lldJson);

    String invalidJsonRetrySuffix();
}
