package com.aiarch.systemdesign.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationEvent {
    private String designId;
    private String stageName;
    private GenerationStatus status;
    private int progressPercentage;
    private String payload;
}
