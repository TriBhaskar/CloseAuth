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
     * Find configuration by OAuth2 client ID (client.clientId field)
     */
    Optional<ApplicationRegistrationConfig> findByClient_ClientId(String clientId);

    /**
     * Check if configuration exists for a client by OAuth2 client ID
     */
    boolean existsByClient_ClientId(String clientId);

    /**
     * Delete configuration by OAuth2 client ID
     */
    void deleteByClient_ClientId(String clientId);
}

