package com.anterka.closeauthbackend.client.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThemeConfigResponse {

    private Long id;
    private Long themeId;
    private String configKey;
    private String configValue;
    private String configType;
    private Instant createdAt;
    private Instant updatedAt;
}
