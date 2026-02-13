package com.aiarch.systemdesign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
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
public class ProjectRequestDTO {

    @NotBlank(message = "Name is required")
    private String name;

    private String platform;

    private String techPreference;

    @Positive(message = "Expected users must be greater than 0")
    private Long expectedUsers;

    @Positive(message = "Expected RPS must be greater than 0")
    private Long expectedRps;

    private String region;
}
