package com.anterka.closeauthbackend.resourceserver.repository;

import com.anterka.closeauthbackend.resourceserver.entity.ClientAuthorizedResourceServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link ClientAuthorizedResourceServer} join entity, queried
 * directly to resolve which Resource Servers a client may target. The client is
 * identified by the SAS {@code oauth2_registered_client(id)} PK (a String).
 */
@Repository
public interface ClientAuthorizedResourceServerRepository
        extends JpaRepository<ClientAuthorizedResourceServer, UUID> {

    List<ClientAuthorizedResourceServer> findByClientRegisteredId(String clientRegisteredId);

    Optional<ClientAuthorizedResourceServer> findByClientRegisteredIdAndResourceServerId(
            String clientRegisteredId, UUID resourceServerId);

    List<ClientAuthorizedResourceServer> findByResourceServerId(UUID resourceServerId);
}
