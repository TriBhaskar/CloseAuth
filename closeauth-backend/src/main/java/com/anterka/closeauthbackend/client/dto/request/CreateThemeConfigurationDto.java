package com.anterka.closeauthbackend.client.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateThemeConfigurationDto {

    @NotBlank(message = "Config key is required")
    @Size(max = 100, message = "Config key must not exceed 100 characters")
    private String configKey;

    @NotBlank(message = "Config value is required")
    private String configValue;

    @Pattern(regexp = "string|number|boolean|json|color", message = "Config type must be one of: string, number, boolean, json, color")
    private String configType = "string";
}

