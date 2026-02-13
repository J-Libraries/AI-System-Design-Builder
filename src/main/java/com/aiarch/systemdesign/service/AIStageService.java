package com.aiarch.systemdesign.service;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignStageResult;

public interface AIStageService {

    DesignStageResult generateHLD(DesignRequestDTO request);

    DesignStageResult generateComponentBreakdown(DesignStageResult hld);

    DesignStageResult generateLLD(DesignStageResult componentBreakdown);

    DesignStageResult generateDataFlow(DesignStageResult hld, DesignStageResult lld);

    DesignStageResult generateScalingStrategy(DesignStageResult hld);

    DesignStageResult generateFailureHandling(DesignStageResult hld);

    DesignStageResult generateDiagramMetadata(DesignStageResult hld, DesignStageResult lld);
}
