package com.aiarch.systemdesign.controller;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignGenerationResponse;
import com.aiarch.systemdesign.service.DesignOrchestratorService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/design")
@RequiredArgsConstructor
public class DesignController {

    private final DesignOrchestratorService designOrchestratorService;

    @PostMapping("/generate")
    public ResponseEntity<DesignGenerationResponse> generateDesign(@Valid @RequestBody DesignRequestDTO request) {
        UUID designId = UUID.randomUUID();
        designOrchestratorService.generateDesignAsync(designId, request);
        DesignGenerationResponse response = DesignGenerationResponse.builder()
                .designId(designId)
                .status("PROCESSING")
                .build();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
