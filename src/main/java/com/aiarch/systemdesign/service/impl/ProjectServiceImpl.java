package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.ProjectRequestDTO;
import com.aiarch.systemdesign.dto.ProjectResponseDTO;
import com.aiarch.systemdesign.exception.ResourceNotFoundException;
import com.aiarch.systemdesign.model.Project;
import com.aiarch.systemdesign.repository.ProjectRepository;
import com.aiarch.systemdesign.service.ProjectService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectServiceImpl.class);

    private final ProjectRepository projectRepository;

    @Override
    public ProjectResponseDTO createProject(ProjectRequestDTO request) {
        log.info("Creating project with name={}", request.getName());
        Project project = Project.builder()
                .name(request.getName())
                .platform(request.getPlatform())
                .techPreference(request.getTechPreference())
                .expectedUsers(request.getExpectedUsers())
                .expectedRps(request.getExpectedRps())
                .region(request.getRegion())
                .build();

        Project savedProject = projectRepository.save(project);
        return toResponse(savedProject);
    }

    @Override
    public ProjectResponseDTO getProject(UUID id) {
        log.info("Fetching project by id={}", id);
        Project project = findByIdOrThrow(id);
        return toResponse(project);
    }

    @Override
    public List<ProjectResponseDTO> listProjects() {
        log.info("Fetching all projects");
        return projectRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public Project getProjectEntity(UUID id) {
        log.info("Fetching project entity by id={}", id);
        return findByIdOrThrow(id);
    }

    private Project findByIdOrThrow(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));
    }

    private ProjectResponseDTO toResponse(Project project) {
        return ProjectResponseDTO.builder()
                .id(project.getId())
                .name(project.getName())
                .platform(project.getPlatform())
                .techPreference(project.getTechPreference())
                .expectedUsers(project.getExpectedUsers())
                .expectedRps(project.getExpectedRps())
                .region(project.getRegion())
                .createdAt(project.getCreatedAt())
                .build();
    }
}
