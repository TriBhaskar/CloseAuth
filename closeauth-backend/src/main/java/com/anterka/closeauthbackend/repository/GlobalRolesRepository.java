package com.anterka.closeauthbackend.repository;

import com.anterka.closeauthbackend.entities.GlobalRoles;
import com.anterka.closeauthbackend.enums.GlobalRoleEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GlobalRolesRepository extends JpaRepository<GlobalRoles,Integer> {
    Optional<GlobalRoles> findByRole(GlobalRoleEnum globalRoleEnum);
}
