package com.aiarch.systemdesign.dto;

import java.time.LocalDateTime;
import java.util.UUID;
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
public class DesignSummaryDTO {
    private UUID id;
    private String productName;
    private Integer version;
    private LocalDateTime createdAt;
}
