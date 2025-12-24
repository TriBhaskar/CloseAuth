package com.anterka.closeauthbackend.client.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "client_themes",
        uniqueConstraints = @UniqueConstraint(columnNames = {"client_id", "theme_name"}))
public class ClientThemes implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", length = 100, nullable = false)
    private String clientId;

    @Column(name = "theme_name", length = 100, nullable = false)
    private String themeName;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    // Light mode colors
    @Column(name = "light_primary_color", length = 7)
    private String lightPrimaryColor;

    @Column(name = "light_background_color", length = 7)
    private String lightBackgroundColor;

    @Column(name = "light_button_color", length = 7)
    private String lightButtonColor;

    @Column(name = "light_text_color", length = 7)
    private String lightTextColor;

    // Dark mode colors
    @Column(name = "dark_primary_color", length = 7)
    private String darkPrimaryColor;

    @Column(name = "dark_background_color", length = 7)
    private String darkBackgroundColor;

    @Column(name = "dark_button_color", length = 7)
    private String darkButtonColor;

    @Column(name = "dark_text_color", length = 7)
    private String darkTextColor;

    // User preferences
    @Column(name = "default_mode", length = 10)
    private String defaultMode; // 'light', 'dark', 'system'

    @Column(name = "allow_mode_toggle", nullable = false)
    @Builder.Default
    private Boolean allowModeToggle = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", insertable = false, updatable = false)
    private Client client;

    @OneToMany(mappedBy = "clientTheme", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ThemeConfigurations> themeConfigurations;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}