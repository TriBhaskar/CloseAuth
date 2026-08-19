package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.tenant.dto.EntryResolutionView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * FE-2a (spec §6.1): the public workspace-entry resolution endpoint — the bridge to the {@code /}
 * "Sign in to your workspace" screen. Unauthenticated by construction (the tenant isn't known to
 * the caller yet), so — like {@link BrandingController} — it must expose only what's needed to
 * proceed and nothing that lets an unauthenticated caller enumerate tenant internals.
 *
 * <h2>HTTP contract</h2>
 * {@code GET /entry/resolve?tenantId=ten_...} → <b>200</b> {@link EntryResolutionView} for a live
 * ({@code ACTIVE}) tenant only; <b>404</b>, empty body, for everything else — unknown,
 * {@code PROVISIONING}, {@code SUSPENDED}, the soft-deleted {@code DELETED}, and a malformed
 * {@code tenantId} are all the SAME response, by construction (never a distinguishing message or
 * status). Per spec, rate-limiting this "tenant-existence oracle" is the Go BFF's responsibility
 * (its own {@code /api/entry/resolve}), not this endpoint's — the backend never sees the caller's
 * real IP (only the BFF's own, since it sits behind that proxy), so a backend-side per-IP limit
 * here would throttle every frontend user collectively rather than the actual abuser.
 */
@RestController
@RequiredArgsConstructor
public class EntryController {

    // Mirrors spec §6.1's field-validation regex exactly — malformed input never reaches the
    // database (a query for a tenantId shape that could never be valid is pointless), and it's
    // one more layer against slug-shaped injection, the same posture as the BFF's own validSlug.
    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^ten_[a-z0-9][a-z0-9-]{1,45}$");

    private final TenantService tenantService;

    @GetMapping(value = "/entry/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EntryResolutionView> resolve(@RequestParam("tenantId") String tenantId) {
        if (!TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            return ResponseEntity.notFound().build();
        }
        return tenantService.resolveActiveTenantBySlug(tenantId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
