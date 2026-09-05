-- ===== Client update/delete: relax two audit_events RESTRICT FKs =====
--
-- audit_events.actor_client_id and audit_events.resource_server_id were both declared
-- ON DELETE RESTRICT in V1 (see V1__initial_schema.sql, "Section 13 — Audit layer"). That
-- posture made sense before any deletion path existed for either principal, but it now
-- blocks the very features it was meant to protect:
--
--   - Every client persisted via ClientRegistrationService.registerClient() already gets
--     a CLIENT_REGISTERED audit_events row pointing at it (actor_client_id), and secret
--     rotation adds a second. A hard client DELETE would violate this FK unconditionally.
--   - Deleting a client also deletes its 1:1 auto-created resource server (nothing else
--     cascades it — resource_servers has no FK to the client). That RS is blocked by the
--     identical RESTRICT the moment a SCOPE_DEFINED/SCOPE_REMOVED/RESOURCE_SERVER_CREATED
--     event references it.
--
-- audit_events is an append-only forensic log; it must be able to outlive the principals
-- it records (a deleted client's history should not become undeletable evidence that
-- blocks the deletion). event_data already carries the human-readable identifiers
-- (client_registered_id, client_id, resource_server_id) as JSONB text, so no information
-- is lost by letting the FK go NULL-on-nothing (i.e. simply unenforced) rather than
-- RESTRICT. The columns, and both indexes-not-taken noted in V1, are unchanged.
--
-- Constraint names below are Postgres's default naming for an unnamed inline REFERENCES
-- (<table>_<column>_fkey) — verified against the V1 DDL, which named neither explicitly.

ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS audit_events_actor_client_id_fkey;
ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS audit_events_resource_server_id_fkey;
