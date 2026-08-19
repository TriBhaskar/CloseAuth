package com.anterka.closeauthbackend.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Command to provision a new tenant (Convention 4 — input DTO, distinct from the
 * output {@link TenantView}).
 *
 * <p>The operator supplies only a name — the public Tenant ID (the {@code ten_}-prefixed slug)
 * is server-derived from it by {@link com.anterka.closeauthbackend.tenant.service.TenantSlugGenerator}
 * (spec §1.2). There is no operator-authored slug input anywhere; the ID is immutable once
 * assigned.
 *
 * <p>Structural validation is expressed as Jakarta Bean Validation annotations
 * (Convention 5). These are enforced at the HTTP boundary in Stage 7 via {@code @Valid};
 * business rules (ID generation/uniqueness) live in the service, not here.
 *
 * @param name human-readable display name, 2-64 chars per spec §6.3.2's provision dialog.
 */
public record ProvisionTenantCommand(

        @NotBlank
        @Size(min = 2, max = 64)
        String name

) {}
