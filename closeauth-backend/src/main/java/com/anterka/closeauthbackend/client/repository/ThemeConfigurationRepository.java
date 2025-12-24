package com.anterka.closeauthbackend.client.repository;

import com.anterka.closeauthbackend.client.entity.ThemeConfigurations;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ThemeConfigurationRepository extends JpaRepository<ThemeConfigurations, Long> {

    /**
     * Find all configurations for a specific theme
     */
    List<ThemeConfigurations> findByThemeId(Long themeId);

    /**
     * Find a specific configuration by theme ID and config key
     */
    Optional<ThemeConfigurations> findByThemeIdAndConfigKey(Long themeId, String configKey);

    /**
     * Check if a configuration key already exists for a theme
     */
    boolean existsByThemeIdAndConfigKey(Long themeId, String configKey);

    /**
     * Delete all configurations for a specific theme
     */
    void deleteByThemeId(Long themeId);

    /**
     * Delete a specific configuration by theme ID and config key
     */
    void deleteByThemeIdAndConfigKey(Long themeId, String configKey);
}

