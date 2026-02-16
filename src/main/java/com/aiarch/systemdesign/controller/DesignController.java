package com.aiarch.systemdesign.controller;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignGenerationResponse;
import com.aiarch.systemdesign.dto.document.SystemDesignDocument;
import com.aiarch.systemdesign.service.DesignDocumentService;
import com.aiarch.systemdesign.service.DesignOrchestratorService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/design")
@RequiredArgsConstructor
public class DesignController {

    private final DesignOrchestratorService designOrchestratorService;
    private final DesignDocumentService designDocumentService;

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

    @GetMapping("/{id}/document")
    public ResponseEntity<SystemDesignDocument> getDocument(@PathVariable("id") UUID designId) {
        return ResponseEntity.ok(designDocumentService.getDocument(designId));
    }

    @PutMapping("/{id}/document")
    public ResponseEntity<SystemDesignDocument> updateDocument(
            @PathVariable("id") UUID designId,
            @RequestBody SystemDesignDocument document
    ) {
        return ResponseEntity.ok(designDocumentService.updateDocument(designId, document));
    }

    @GetMapping("/{id}/export/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable("id") UUID designId) {
        byte[] pdfData = designDocumentService.exportDocumentPdf(designId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=system-design-" + designId + ".pdf")
                .body(pdfData);
    }
}
