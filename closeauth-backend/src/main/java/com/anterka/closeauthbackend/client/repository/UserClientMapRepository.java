package com.anterka.closeauthbackend.client.repository;

import com.anterka.closeauthbackend.client.entity.UserClientMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserClientMapRepository extends JpaRepository<UserClientMap, Integer> {

    /**
     * Find mapping by user ID and client ID
     */
    Optional<UserClientMap> findByUser_IdAndClient_Id(Integer userId, String clientId);

    /**
     * Find all mappings for a specific user
     */
    List<UserClientMap> findByUser_Id(Integer userId);

    /**
     * Find all mappings for a specific client
     */
    List<UserClientMap> findByClient_Id(String clientId);

    /**
     * Check if mapping exists
     */
    boolean existsByUser_IdAndClient_Id(Integer userId, String clientId);
}

