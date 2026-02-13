package com.aiarch.systemdesign.service;

import com.aiarch.systemdesign.dto.DesignRequestDTO;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface DesignOrchestratorService {

    CompletableFuture<Void> generateDesignAsync(UUID designId, DesignRequestDTO request);
}
