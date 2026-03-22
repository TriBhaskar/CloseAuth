package com.anterka.closeauthbackend.client.repository;

import com.anterka.closeauthbackend.client.entity.Client;
import com.anterka.closeauthbackend.client.entity.ApplicationRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRoleRepository extends JpaRepository<ApplicationRole, Integer> {

    /**
     * Find all roles for a specific client
     */
    List<ApplicationRole> findByClient(Client client);

    /**
     * Find all roles for a specific client ID
     */
    List<ApplicationRole> findByClientId(String clientId);

    /**
     * Find a specific role by client and role name
     */
    Optional<ApplicationRole> findByClientAndRoleName(Client client, String roleName);

    /**
     * Find a specific role by client ID and role name
     */
    Optional<ApplicationRole> findByClientIdAndRoleName(String clientId, String roleName);

    /**
     * Find all default roles for a specific client
     */
    List<ApplicationRole> findByClientAndIsDefaultTrue(Client client);

    /**
     * Find all default roles for a specific client ID
     */
    List<ApplicationRole> findByClientIdAndIsDefaultTrue(String clientId);

    /**
     * Check if a role exists for a client with the given role name
     */
    boolean existsByClientIdAndRoleName(String clientId, String roleName);
}

