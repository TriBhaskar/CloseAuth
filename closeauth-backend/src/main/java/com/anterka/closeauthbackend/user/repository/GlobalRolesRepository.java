package com.anterka.closeauthbackend.user.repository;

import com.anterka.closeauthbackend.user.entity.GlobalRoles;
import com.anterka.closeauthbackend.user.enums.GlobalRoleEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GlobalRolesRepository extends JpaRepository<GlobalRoles,Integer> {
    Optional<GlobalRoles> findByRole(GlobalRoleEnum globalRoleEnum);
}
