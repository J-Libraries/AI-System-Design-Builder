package com.aiarch.systemdesign.dto;

import java.time.LocalDateTime;
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
public class DesignStageResult {
    private String stageName;
    private String content;
    private LocalDateTime createdAt;
}
