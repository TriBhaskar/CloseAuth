package com.anterka.closeauthbackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThemeResponse {

    private Long id;
    private String clientId;
    private String themeName;
    private Boolean isActive;
    private Boolean isDefault;
    private String logoUrl;
    private String lightPrimaryColor;
    private String lightBackgroundColor;
    private String lightButtonColor;
    private String lightTextColor;
    private String darkPrimaryColor;
    private String darkBackgroundColor;
    private String darkButtonColor;
    private String darkTextColor;
    private String defaultMode;
    private Boolean allowModeToggle;
    private Instant createdAt;
    private Instant updatedAt;
}

