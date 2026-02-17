package com.aiarch.systemdesign.controller;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import com.aiarch.systemdesign.dto.DesignGenerationResponse;
import com.aiarch.systemdesign.dto.DesignSummaryDTO;
import com.aiarch.systemdesign.dto.document.SystemDesignDocument;
import com.aiarch.systemdesign.service.DesignDocumentService;
import com.aiarch.systemdesign.service.DesignManagementService;
import com.aiarch.systemdesign.service.DesignOrchestratorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final DesignManagementService designManagementService;

    @GetMapping
    public ResponseEntity<List<DesignSummaryDTO>> listDesigns() {
        return ResponseEntity.ok(designManagementService.listDesigns());
    }

    @PostMapping("/generate")
    public ResponseEntity<DesignGenerationResponse> generateDesign(@Valid @RequestBody DesignRequestDTO request) {
        UUID designId = UUID.randomUUID();
        designOrchestratorService.initializeDesign(designId, request);
        designOrchestratorService.generateDesignAsync(designId, request);
        DesignGenerationResponse response = DesignGenerationResponse.builder()
                .designId(designId)
                .status("PROCESSING")
                .build();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{id}/regenerate")
    public ResponseEntity<DesignGenerationResponse> regenerateDesign(
            @PathVariable("id") UUID designId,
            @Valid @RequestBody DesignRequestDTO request
    ) {
        DesignRequestDTO existingRequest = designManagementService.getDesignRequest(designId);
        if (isEquivalentRegenerationRequest(existingRequest, request)) {
            DesignGenerationResponse response = DesignGenerationResponse.builder()
                    .designId(designId)
                    .status("READY")
                    .build();
            return ResponseEntity.ok(response);
        }

        designOrchestratorService.initializeDesign(designId, request);
        designOrchestratorService.generateDesignAsync(designId, request);
        DesignGenerationResponse response = DesignGenerationResponse.builder()
                .designId(designId)
                .status("PROCESSING")
                .build();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    private boolean isEquivalentRegenerationRequest(DesignRequestDTO existing, DesignRequestDTO incoming) {
        if (existing == null || incoming == null) {
            return false;
        }
        return equalsIgnoreTrim(existing.getProductName(), incoming.getProductName())
                && normalizeRequirements(existing.getFunctionalRequirements())
                .equals(normalizeRequirements(incoming.getFunctionalRequirements()))
                && Objects.equals(existing.getExpectedDAU(), incoming.getExpectedDAU())
                && equalsIgnoreTrim(existing.getRegion(), incoming.getRegion())
                && Objects.equals(existing.getScale(), incoming.getScale())
                && Objects.equals(existing.getTargetPlatform(), incoming.getTargetPlatform())
                && Objects.equals(existing.getDesignDomain(), incoming.getDesignDomain())
                && equalsIgnoreTrim(existing.getTechStackChoice(), incoming.getTechStackChoice())
                && equalsIgnoreTrim(existing.getDatabaseChoice(), incoming.getDatabaseChoice())
                && equalsIgnoreTrim(existing.getServerType(), incoming.getServerType())
                && equalsIgnoreTrim(existing.getContainerStrategy(), incoming.getContainerStrategy());
    }

    private List<String> normalizeRequirements(List<String> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private boolean equalsIgnoreTrim(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        return a.equalsIgnoreCase(b);
    }

    @GetMapping("/{id}/request")
    public ResponseEntity<DesignRequestDTO> getDesignRequest(@PathVariable("id") UUID designId) {
        return ResponseEntity.ok(designManagementService.getDesignRequest(designId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDesign(@PathVariable("id") UUID designId) {
        designManagementService.deleteDesign(designId);
        return ResponseEntity.noContent().build();
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

    @GetMapping("/{id}/export/sow/pdf")
    public ResponseEntity<byte[]> exportSowPdf(@PathVariable("id") UUID designId) {
        byte[] pdfData = designDocumentService.exportSowPdf(designId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=sow-" + designId + ".pdf")
                .body(pdfData);
    }

    @GetMapping("/{id}/export/task-breakdown/csv")
    public ResponseEntity<byte[]> exportTaskBreakdownCsv(@PathVariable("id") UUID designId) {
        byte[] csvData = designDocumentService.exportTaskBreakdownCsv(designId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=task-breakdown-" + designId + ".csv")
                .body(csvData);
    }
}
