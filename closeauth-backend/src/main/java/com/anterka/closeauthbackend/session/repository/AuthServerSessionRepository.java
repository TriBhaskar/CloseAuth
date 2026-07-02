package com.anterka.closeauthbackend.session.repository;

import com.anterka.closeauthbackend.session.entity.AuthServerSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link AuthServerSession} aggregate root (the durable session
 * ledger).
 *
 * <p>{@code findBySessionKey} is legitimately tenant-unscoped: {@code session_key} is
 * the globally-unique opaque cookie value that self-identifies the session. The
 * user/tenant listing supports the "list my active sessions" endpoint (Section 7.5).
 */
@Repository
public interface AuthServerSessionRepository extends JpaRepository<AuthServerSession, UUID> {

    /** Global by design: session_key is the globally-unique opaque cookie value. */
    Optional<AuthServerSession> findBySessionKey(String sessionKey);

    List<AuthServerSession> findByUserIdAndTenantId(UUID userId, UUID tenantId);
}
