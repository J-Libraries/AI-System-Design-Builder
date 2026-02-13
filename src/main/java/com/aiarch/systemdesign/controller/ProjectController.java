package com.aiarch.systemdesign.controller;

import com.aiarch.systemdesign.dto.ArchitectureResponse;
import com.aiarch.systemdesign.dto.ProjectRequestDTO;
import com.aiarch.systemdesign.dto.ProjectResponseDTO;
import com.aiarch.systemdesign.model.Project;
import com.aiarch.systemdesign.orchestrator.AiOrchestratorService;
import com.aiarch.systemdesign.service.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final AiOrchestratorService aiOrchestratorService;

    @PostMapping
    public ResponseEntity<ProjectResponseDTO> createProject(@Valid @RequestBody ProjectRequestDTO request) {
        ProjectResponseDTO response = projectService.createProject(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponseDTO>> listProjects() {
        return ResponseEntity.ok(projectService.listProjects());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponseDTO> getProject(@PathVariable UUID id) {
        return ResponseEntity.ok(projectService.getProject(id));
    }

    @PostMapping("/{id}/generate-hld")
    public ResponseEntity<ArchitectureResponse> generateHighLevelDesign(@PathVariable UUID id) {
        Project project = projectService.getProjectEntity(id);
        ArchitectureResponse response = aiOrchestratorService.generateBasicArchitecture(project);
        return ResponseEntity.ok(response);
    }
}
