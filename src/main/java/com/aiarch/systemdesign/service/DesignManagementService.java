package com.aiarch.systemdesign.service;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignSummaryDTO;
import java.util.List;
import java.util.UUID;

public interface DesignManagementService {

    List<DesignSummaryDTO> listDesigns();

    DesignRequestDTO getDesignRequest(UUID designId);

    void deleteDesign(UUID designId);
}
