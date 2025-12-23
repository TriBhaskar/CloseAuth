package com.anterka.closeauthbackend.core.repository;

import com.anterka.closeauthbackend.core.entities.ClientThemes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientThemeRepository extends JpaRepository<ClientThemes, Long> {

    /**
     * Find all themes for a specific client
     */
    List<ClientThemes> findByClientId(String clientId);

    /**
     * Find a specific theme by client ID and theme name
     */
    Optional<ClientThemes> findByClientIdAndThemeName(String clientId, String themeName);

    /**
     * Find the active theme for a specific client
     */
    Optional<ClientThemes> findByClientIdAndIsActiveTrue(String clientId);

    /**
     * Find the default theme for a specific client
     */
    Optional<ClientThemes> findByClientIdAndIsDefaultTrue(String clientId);

    /**
     * Check if a theme name already exists for a client
     */
    boolean existsByClientIdAndThemeName(String clientId, String themeName);

    /**
     * Deactivate all themes for a specific client
     */
    @Modifying
    @Query("UPDATE ClientThemes ct SET ct.isActive = false WHERE ct.clientId = :clientId")
    void deactivateAllThemesForClient(@Param("clientId") String clientId);

    /**
     * Delete all themes for a specific client
     */
    void deleteByClientId(String clientId);
}

