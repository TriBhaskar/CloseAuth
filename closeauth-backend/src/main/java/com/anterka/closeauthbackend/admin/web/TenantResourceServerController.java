package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.web.PageView;
import com.anterka.closeauthbackend.resourceserver.dto.AddScopeCommand;
import com.anterka.closeauthbackend.resourceserver.dto.CreateResourceServerCommand;
import com.anterka.closeauthbackend.resourceserver.dto.ResourceServerView;
import com.anterka.closeauthbackend.resourceserver.dto.ScopeView;
import com.anterka.closeauthbackend.resourceserver.dto.UpdateResourceServerCommand;
import com.anterka.closeauthbackend.resourceserver.dto.UpdateScopeCommand;
import com.anterka.closeauthbackend.resourceserver.service.ResourceServerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tenant-scoped resource-server + scope administration (§7.8). {@link RequiresTenantAccess} gate. Wraps
 * {@link ResourceServerService} (3c-i); the immutability rules (audience immutable, slug mutable, scope_name immutable)
 * are enforced by the service and surface as RFC 7807.
 *
 * <h2>HTTP contract</h2>
 * {@code GET/POST /resource-servers} · {@code GET/PATCH/DELETE /resource-servers/{rsId}} ·
 * {@code GET/POST /resource-servers/{rsId}/scopes} · {@code PATCH/DELETE /resource-servers/{rsId}/scopes/{scopeId}}.
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}/resource-servers")
public class TenantResourceServerController {

    private final ResourceServerService resourceServerService;

    @GetMapping
    public PageView<ResourceServerView> list(@PathVariable String tenantId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return PageView.of(resourceServerService.listResourceServers(ctx(tenantId)), page, size);
    }

    @PostMapping
    public ResponseEntity<ResourceServerView> create(@PathVariable String tenantId,
                                                     @Valid @RequestBody CreateResourceServerCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(resourceServerService.createResourceServer(ctx(tenantId), command));
    }

    @GetMapping("/{rsId}")
    public ResourceServerView get(@PathVariable String tenantId, @PathVariable UUID rsId) {
        return resourceServerService.getResourceServerById(ctx(tenantId), rsId);
    }

    @PatchMapping("/{rsId}")
    public ResourceServerView update(@PathVariable String tenantId, @PathVariable UUID rsId,
                                     @Valid @RequestBody UpdateResourceServerCommand command) {
        return resourceServerService.updateResourceServer(ctx(tenantId), rsId, command);
    }

    @DeleteMapping("/{rsId}")
    public ResponseEntity<Void> delete(@PathVariable String tenantId, @PathVariable UUID rsId) {
        resourceServerService.deleteResourceServer(ctx(tenantId), rsId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{rsId}/scopes")
    public PageView<ScopeView> listScopes(@PathVariable String tenantId, @PathVariable UUID rsId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return PageView.of(resourceServerService.listScopes(ctx(tenantId), rsId), page, size);
    }

    @PostMapping("/{rsId}/scopes")
    public ResponseEntity<ScopeView> addScope(@PathVariable String tenantId, @PathVariable UUID rsId,
                                              @Valid @RequestBody AddScopeCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(resourceServerService.addScope(ctx(tenantId), rsId, command));
    }

    @PatchMapping("/{rsId}/scopes/{scopeId}")
    public ScopeView updateScope(@PathVariable String tenantId, @PathVariable UUID rsId, @PathVariable UUID scopeId,
                                 @Valid @RequestBody UpdateScopeCommand command) {
        return resourceServerService.updateScope(ctx(tenantId), rsId, scopeId, command);
    }

    @DeleteMapping("/{rsId}/scopes/{scopeId}")
    public ResponseEntity<Void> removeScope(@PathVariable String tenantId, @PathVariable UUID rsId,
                                            @PathVariable UUID scopeId) {
        resourceServerService.removeScope(ctx(tenantId), rsId, scopeId);
        return ResponseEntity.noContent().build();
    }

    private TenantContext ctx(String tenantId) {
        return TenantContext.of(UUID.fromString(tenantId));
    }
}
