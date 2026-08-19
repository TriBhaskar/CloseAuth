-- BE-A: the public Tenant ID becomes a server-derived, ten_-prefixed value
-- (CLOSEAUTH_FRONTEND_SPEC.md §1.2) instead of an operator-typed slug. Every
-- pre-existing row (if any — no real production tenants exist at this
-- stage) already satisfies the OLD slug pattern
-- (^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ — lowercase alnum+hyphen only, already
-- globally unique), so this backfill only needs a prefix + length clamp, not
-- the full normalize/strip/truncate pipeline TenantSlugGenerator applies to
-- new, free-text names. Prefixing an already-unique set with a constant
-- cannot introduce a new collision, so no dedup step is needed either.
--
-- Order matters: the admin-console client's client_id and
-- post_logout_redirect_uris (the only other place a tenant's OLD slug is
-- baked into persisted state) are rewritten FIRST, while tenants.slug still
-- holds the old value to correlate against. The new value is computed
-- inline (identically in both statements) rather than depending on
-- tenants.slug already being updated.

UPDATE oauth2_registered_client c
SET client_id = 'admin-console-ten_' || left(t.slug, 59),
    post_logout_redirect_uris = replace(
        replace(
            c.post_logout_redirect_uris,
            '/t/' || t.slug || '/console',
            '/t/ten_' || left(t.slug, 59) || '/console'
        ),
        '/t/' || t.slug || '/logged-out',
        '/t/ten_' || left(t.slug, 59) || '/logged-out'
    )
FROM tenants t
WHERE c.tenant_id = t.id
  AND c.client_id = 'admin-console-' || t.slug
  AND t.slug NOT LIKE 'ten\_%' ESCAPE '\';

UPDATE tenants
SET slug = 'ten_' || left(slug, 59)
WHERE slug NOT LIKE 'ten\_%' ESCAPE '\';
