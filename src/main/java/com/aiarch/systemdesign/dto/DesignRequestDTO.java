package com.aiarch.systemdesign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
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
public class DesignRequestDTO {

    @NotBlank(message = "Product name is required")
    private String productName;

    @NotEmpty(message = "Functional requirements are required")
    private List<@NotBlank(message = "Functional requirement cannot be blank") String> functionalRequirements;

    private List<String> nonFunctionalRequirements;

    @NotNull(message = "Expected DAU is required")
    @Positive(message = "Expected DAU must be greater than 0")
    private Long expectedDAU;

    @NotBlank(message = "Region is required")
    private String region;

    @NotNull(message = "Scale is required")
    private DesignScale scale;
}
