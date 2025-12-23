package com.anterka.closeauthbackend.dto.request;

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
public class CreateClientThemeDto {

    @NotBlank(message = "Theme name is required")
    @Size(max = 100, message = "Theme name must not exceed 100 characters")
    private String themeName;

    @Size(max = 500, message = "Logo URL must not exceed 500 characters")
    private String logoUrl;

    // Light mode colors
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format for light primary color")
    private String lightPrimaryColor;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format for light background color")
    private String lightBackgroundColor;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format for light button color")
    private String lightButtonColor;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format for light text color")
    private String lightTextColor;

    // Dark mode colors
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format for dark primary color")
    private String darkPrimaryColor;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format for dark background color")
    private String darkBackgroundColor;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format for dark button color")
    private String darkButtonColor;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format for dark text color")
    private String darkTextColor;

    @Pattern(regexp = "light|dark|system", message = "Default mode must be 'light', 'dark', or 'system'")
    private String defaultMode = "light";

    private Boolean allowModeToggle = true;

    private Boolean isDefault = false;
}

