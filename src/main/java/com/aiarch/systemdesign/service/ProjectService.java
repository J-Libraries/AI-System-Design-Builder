package com.aiarch.systemdesign.service;

import com.aiarch.systemdesign.dto.ProjectRequestDTO;
import com.aiarch.systemdesign.dto.ProjectResponseDTO;
import com.aiarch.systemdesign.model.Project;
import java.util.List;
import java.util.UUID;

public interface ProjectService {

    ProjectResponseDTO createProject(ProjectRequestDTO request);

    ProjectResponseDTO getProject(UUID id);

    List<ProjectResponseDTO> listProjects();

    Project getProjectEntity(UUID id);
}
