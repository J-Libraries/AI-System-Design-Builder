package com.aiarch.systemdesign.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinalDesignResponse {
    private JsonNode hld;
    private JsonNode lld;
    private JsonNode dataFlow;
    private JsonNode scalingStrategy;
    private JsonNode failureHandling;
    private JsonNode diagramMetadata;
}
