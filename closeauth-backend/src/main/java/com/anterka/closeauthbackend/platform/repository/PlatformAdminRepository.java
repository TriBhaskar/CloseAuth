package com.anterka.closeauthbackend.platform.repository;

import com.anterka.closeauthbackend.platform.entity.PlatformAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link PlatformAdmin} aggregate root. Platform admins are NOT tenant-scoped — {@code email} is
 * globally unique, so lookups are global by design (the counterpart to {@code users}' per-tenant lookups).
 */
@Repository
public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, UUID> {

    Optional<PlatformAdmin> findByEmail(String email);

    boolean existsByEmail(String email);
}
