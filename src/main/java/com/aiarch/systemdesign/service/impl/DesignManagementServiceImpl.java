package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignSummaryDTO;
import com.aiarch.systemdesign.exception.ResourceNotFoundException;
import com.aiarch.systemdesign.model.SystemDesign;
import com.aiarch.systemdesign.repository.SystemDesignRepository;
import com.aiarch.systemdesign.service.DesignManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DesignManagementServiceImpl implements DesignManagementService {

    private final SystemDesignRepository systemDesignRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<DesignSummaryDTO> listDesigns() {
        return systemDesignRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(design -> DesignSummaryDTO.builder()
                        .id(design.getId())
                        .productName(design.getProductName())
                        .version(design.getVersion())
                        .createdAt(design.getCreatedAt())
                        .build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DesignRequestDTO getDesignRequest(UUID designId) {
        SystemDesign design = systemDesignRepository.findById(designId)
                .orElseThrow(() -> new ResourceNotFoundException("System design not found with id: " + designId));
        if (design.getRequestJson() == null || design.getRequestJson().isNull()) {
            throw new ResourceNotFoundException("Original prompt not available for design id: " + designId);
        }
        try {
            return objectMapper.convertValue(design.getRequestJson(), DesignRequestDTO.class);
        } catch (IllegalArgumentException ex) {
            throw new ResourceNotFoundException("Saved prompt could not be parsed for design id: " + designId);
        }
    }

    @Override
    @Transactional
    public void deleteDesign(UUID designId) {
        if (!systemDesignRepository.existsById(designId)) {
            throw new ResourceNotFoundException("System design not found with id: " + designId);
        }
        systemDesignRepository.deleteById(designId);
    }
}
