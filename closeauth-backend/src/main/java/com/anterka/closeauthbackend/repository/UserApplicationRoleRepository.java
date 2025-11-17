package com.anterka.closeauthbackend.repository;

import com.anterka.closeauthbackend.entities.ApplicationRole;
import com.anterka.closeauthbackend.entities.UserApplicationRole;
import com.anterka.closeauthbackend.entities.UserClientMap;
import com.anterka.closeauthbackend.entities.Users;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserApplicationRoleRepository extends JpaRepository<UserApplicationRole, Integer> {

    /**
     * Find all roles assigned to a specific user-client mapping
     */
    List<UserApplicationRole> findByUserClientMap(UserClientMap userClientMap);

    /**
     * Find all roles assigned to a specific user-client mapping ID
     */
    List<UserApplicationRole> findByUserClientMapId(Integer userClientMapId);

    /**
     * Find a specific role assignment
     */
    Optional<UserApplicationRole> findByUserClientMapAndApplicationRole(
            UserClientMap userClientMap,
            ApplicationRole applicationRole
    );

    /**
     * Find all users assigned to a specific application role
     */
    List<UserApplicationRole> findByApplicationRole(ApplicationRole applicationRole);

    /**
     * Find all users assigned to a specific application role ID
     */
    List<UserApplicationRole> findByApplicationRoleId(Integer applicationRoleId);

    /**
     * Check if a user-client mapping has a specific role
     */
    boolean existsByUserClientMapIdAndApplicationRoleId(Integer userClientMapId, Integer applicationRoleId);

    /**
     * Delete all role assignments for a user-client mapping
     */
    void deleteByUserClientMap(UserClientMap userClientMap);

    /**
     * Delete a specific role assignment
     */
    void deleteByUserClientMapAndApplicationRole(UserClientMap userClientMap, ApplicationRole applicationRole);

    /**
     * Find all roles for a user in a specific client application
     */
    @Query("SELECT uar FROM UserApplicationRole uar " +
           "WHERE uar.userClientMap.user = :user " +
           "AND uar.userClientMap.client.id = :clientId")
    List<UserApplicationRole> findUserRolesInClient(@Param("user") Users user, @Param("clientId") String clientId);

    /**
     * Find all role assignments assigned by a specific user
     */
    List<UserApplicationRole> findByAssignedBy(Users assignedBy);
}

