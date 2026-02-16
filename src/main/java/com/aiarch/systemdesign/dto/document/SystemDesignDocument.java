package com.aiarch.systemdesign.dto.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
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
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemDesignDocument {

    private String sow;
    private String overview;
    private List<String> assumptions;
    private String capacityEstimation;
    private String hld;
    private List<Component> components;
    private List<ComponentLLD> lld;
    private List<ApiContract> apiContracts;
    private List<DatabaseSchema> databaseSchemas;
    private List<DataFlowScenario> dataFlowScenarios;
    private String scalingStrategy;
    private String failureHandling;
    private String tradeoffs;
    private List<TaskBreakdownItem> taskBreakdown;
    private String wireframeSummary;
    private List<WireframeScreen> wireframeScreens;
    private DiagramMetadata diagramMetadata;
}
