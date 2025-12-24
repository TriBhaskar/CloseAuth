package com.anterka.closeauthbackend.client.repository;

import com.anterka.closeauthbackend.client.entity.Client;
import com.anterka.closeauthbackend.client.entity.ApplicationRegistrationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ApplicationRegistrationConfigRepository extends JpaRepository<ApplicationRegistrationConfig, Integer> {

    /**
     * Find configuration for a specific client
     */
    Optional<ApplicationRegistrationConfig> findByClient(Client client);

    /**
     * Find configuration by client ID
     */
    Optional<ApplicationRegistrationConfig> findByClientId(String clientId);

    /**
     * Check if configuration exists for a client
     */
    boolean existsByClientId(String clientId);

    /**
     * Delete configuration by client ID
     */
    void deleteByClientId(String clientId);
}

