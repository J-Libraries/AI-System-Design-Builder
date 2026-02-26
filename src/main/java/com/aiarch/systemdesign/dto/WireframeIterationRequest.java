package com.aiarch.systemdesign.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WireframeIterationRequest {
    @NotBlank(message = "Prompt is required for iteration")
    private String prompt;
}
