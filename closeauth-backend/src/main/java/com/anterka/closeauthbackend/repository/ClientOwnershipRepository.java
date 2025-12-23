package com.anterka.closeauthbackend.repository;

import com.anterka.closeauthbackend.entities.ClientOwnerShip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientOwnershipRepository extends JpaRepository<ClientOwnerShip, Integer> {

    /**
     * Find ownership by client ID and user ID
     */
    Optional<ClientOwnerShip> findByClient_IdAndUser_Id(String clientId, Integer userId);

    /**
     * Check if a user owns a specific client
     */
    boolean existsByClient_IdAndUser_Id(String clientId, Integer userId);

    /**
     * Find ownership by client ID
     */
    Optional<ClientOwnerShip> findByClient_Id(String clientId);

    /**
     * Find all clients owned by a user
     */
    List<ClientOwnerShip> findByUser_Id(Integer userId);
}

