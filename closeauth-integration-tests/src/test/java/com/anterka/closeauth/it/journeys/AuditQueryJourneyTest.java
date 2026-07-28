package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.AdminApiClient.ClientCredentials;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-8's audit query API journey, black-box — the capstone. Every prior stage has been generating real audit events
 * through the async outbox→drain pipeline; this stage is the first to query them. It proves the two query endpoints
 * (tenant-scoped {@code /v1/tenants/{tid}/audit-events} and platform {@code /v1/platform/audit-events}), their filters,
 * pagination, and — the severe-bug-class check — that tenant isolation holds on the query layer both directions.
 *
 * <p><b>Deterministic, not retroactive:</b> per the stage design, this class asserts ONLY on events produced by its own
 * fresh fixtures (unique tenants/users), never on other test classes' data or on execution order. Because audit is an
 * async outbox→drain pipeline (2s drain interval), fixtures generate the events and then <b>poll</b> the query API until
 * each key event has drained (the same "wait, don't sleep" discipline as the Mailpit flows) before any assertion runs.
 */
class AuditQueryJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private static String platformToken;
    private static String tenantA;
    private static String tenantB;
    private static String tenantAdminTokenA;
    private static String tenantAdminTokenB;
    private static String adminAId;        // subject of a known Tenant-A login (and its TOKEN_ISSUED)
    private static String userTargetAId;   // subject of suspend/activate/role-assign + a second Tenant-A login
    private static String adminBId;        // subject of the Tenant-B-only login (isolation proof)

    @BeforeAll
    static void fixtures() {
        AdminApiClient admin = adminApi();
        platformToken = platformAdminToken();

        // ----- Tenant A: an admin (its login is a known event) + a target user driven through several actions -----
        tenantA = admin.provisionActiveTenant(platformToken);
        ClientCredentials clientA = admin.registerConfidentialClient(platformToken, tenantA);

        String adminAEmail = Fixtures.email("audit-admin-a");
        adminAId = admin.createActiveUser(platformToken, tenantA, adminAEmail, PASSWORD);
        admin.assignTenantRole(platformToken, tenantA, adminAId, "TENANT_ADMIN");
        tenantAdminTokenA = oauthFlow().login(clientA.clientId(), clientA.secret(), adminAEmail, PASSWORD)
                .tokens().accessToken(); // → USER_LOGIN_SUCCESS + TOKEN_ISSUED, subject=adminA

        String targetEmail = Fixtures.email("audit-target-a");
        userTargetAId = admin.createActiveUser(platformToken, tenantA, targetEmail, PASSWORD);
        oauthFlow().login(clientA.clientId(), clientA.secret(), targetEmail, PASSWORD); // a 2nd distinct login subject
        admin.post(platformToken, "/v1/tenants/{tid}/users/{uid}/suspend", tenantA, userTargetAId);   // USER_SUSPENDED
        admin.post(platformToken, "/v1/tenants/{tid}/users/{uid}/activate", tenantA, userTargetAId);  // USER_ACTIVATED
        admin.assignTenantRole(platformToken, tenantA, userTargetAId, "TENANT_ADMIN");                // ROLE_ASSIGNED

        // ----- Tenant B: one distinct action (its admin's login) — the B-only event to prove isolation against -----
        tenantB = admin.provisionActiveTenant(platformToken);
        ClientCredentials clientB = admin.registerConfidentialClient(platformToken, tenantB);
        String adminBEmail = Fixtures.email("audit-admin-b");
        adminBId = admin.createActiveUser(platformToken, tenantB, adminBEmail, PASSWORD);
        admin.assignTenantRole(platformToken, tenantB, adminBId, "TENANT_ADMIN");
        tenantAdminTokenB = oauthFlow().login(clientB.clientId(), clientB.secret(), adminBEmail, PASSWORD)
                .tokens().accessToken(); // → USER_LOGIN_SUCCESS, subject=adminB, tenant B

        // Wait for the async pipeline to drain the key events before any assertion (2s drain interval; poll up to 30s).
        awaitPresent(() -> queryTenant(tenantAdminTokenA, tenantA,
                params("event_type", "USER_LOGIN_SUCCESS", "user_id", adminAId)));
        awaitPresent(() -> queryTenant(tenantAdminTokenA, tenantA,
                params("event_type", "USER_LOGIN_SUCCESS", "user_id", userTargetAId)));
        awaitPresent(() -> queryTenant(tenantAdminTokenA, tenantA,
                params("event_type", "USER_SUSPENDED", "user_id", userTargetAId)));
        awaitPresent(() -> queryTenant(tenantAdminTokenA, tenantA,
                params("event_type", "USER_ACTIVATED", "user_id", userTargetAId)));
        awaitPresent(() -> queryTenant(tenantAdminTokenA, tenantA,
                params("event_type", "ROLE_ASSIGNED", "user_id", userTargetAId)));
        awaitPresent(() -> queryTenant(tenantAdminTokenB, tenantB,
                params("event_type", "USER_LOGIN_SUCCESS", "user_id", adminBId)));
    }

    // ---- Scenario 1: a known action → a queryable, correctly-shaped event --

    @Test
    @DisplayName("Scenario 1: the Tenant-A login is queryable and correctly shaped; wrong filters exclude it")
    void knownActionProducesCorrectlyShapedEvent() {
        Response present = queryTenant(tenantAdminTokenA, tenantA,
                params("event_type", "USER_LOGIN_SUCCESS", "user_id", adminAId));
        assertThat(total(present)).as("the login event is found").isGreaterThanOrEqualTo(1);

        Map<String, Object> event = firstItem(present);
        assertThat(event.get("eventType")).isEqualTo("USER_LOGIN_SUCCESS");
        assertThat(event.get("outcome")).isEqualTo("SUCCESS");
        assertThat(event.get("tenantId")).as("event is scoped to Tenant A").isEqualTo(tenantA);
        assertThat(event.get("subjectUserId")).as("subject is the logging-in user").isEqualTo(adminAId);

        // Wrong user_id → absent.
        assertThat(total(queryTenant(tenantAdminTokenA, tenantA,
                params("event_type", "USER_LOGIN_SUCCESS", "user_id", UUID.randomUUID().toString()))))
                .as("an unrelated user_id matches nothing").isZero();
        // Wrong event_type for this user → absent (adminA never logged out).
        assertThat(total(queryTenant(tenantAdminTokenA, tenantA,
                params("event_type", "USER_LOGOUT", "user_id", adminAId))))
                .as("the wrong event_type excludes the login").isZero();
    }

    // ---- Scenario 2: time-range filtering, to-exclusive boundary ----------

    @Test
    @DisplayName("Scenario 2: from/to filtering respects the documented to-exclusive, from-inclusive boundary")
    void timeRangeFilteringIsToExclusiveFromInclusive() {
        // Isolate exactly one event: the target user's suspension. Read its exact createdAt.
        Map<String, String> suspendFilter = params("event_type", "USER_SUSPENDED", "user_id", userTargetAId);
        Response base = queryTenant(tenantAdminTokenA, tenantA, suspendFilter);
        assertThat(total(base)).as("exactly one suspension event for the target user").isEqualTo(1);
        Instant suspendedAt = Instant.parse((String) firstItem(base).get("createdAt"));

        // A window that straddles it → present.
        assertThat(total(queryTenant(tenantAdminTokenA, tenantA, withRange(suspendFilter,
                suspendedAt.minusSeconds(60), suspendedAt.plusSeconds(60)))))
                .as("a window that contains the event includes it").isEqualTo(1);

        // A window entirely before it → absent.
        assertThat(total(queryTenant(tenantAdminTokenA, tenantA, withRange(suspendFilter,
                suspendedAt.minusSeconds(120), suspendedAt.minusSeconds(60)))))
                .as("a window entirely before the event excludes it").isZero();

        // to EXACTLY equal to the event's createdAt → EXCLUDED (proves `to` is exclusive, i.e. createdAt < to).
        assertThat(total(queryTenant(tenantAdminTokenA, tenantA, withRange(suspendFilter,
                suspendedAt.minusSeconds(60), suspendedAt))))
                .as("to == createdAt excludes the event (to is exclusive)").isZero();

        // from == createdAt (inclusive) with to just past it → PRESENT (proves `from` is inclusive).
        assertThat(total(queryTenant(tenantAdminTokenA, tenantA, withRange(suspendFilter,
                suspendedAt, suspendedAt.plusMillis(1)))))
                .as("from == createdAt includes the event (from is inclusive)").isEqualTo(1);
    }

    // ---- Scenario 3: combined filters intersect ---------------------------

    @Test
    @DisplayName("Scenario 3: two filters combine as an intersection, narrower than either alone")
    void combinedFiltersIntersect() {
        long byType = total(queryTenant(tenantAdminTokenA, tenantA, params("event_type", "USER_LOGIN_SUCCESS")));
        long byUser = total(queryTenant(tenantAdminTokenA, tenantA, params("user_id", adminAId)));
        Response combined = queryTenant(tenantAdminTokenA, tenantA,
                params("event_type", "USER_LOGIN_SUCCESS", "user_id", adminAId));
        long both = total(combined);

        // event_type alone also matches the target user's login; user_id alone also matches adminA's TOKEN_ISSUED.
        assertThat(byType).as("more than one login exists in Tenant A").isGreaterThanOrEqualTo(2);
        assertThat(byUser).as("adminA is the subject of more than one event type").isGreaterThanOrEqualTo(2);
        assertThat(both).as("the intersection is non-empty").isGreaterThanOrEqualTo(1);
        assertThat(both).as("intersection is strictly narrower than event_type alone").isLessThan(byType);
        assertThat(both).as("intersection is strictly narrower than user_id alone").isLessThan(byUser);

        // Every returned row satisfies BOTH filters.
        for (Map<String, Object> item : items(combined)) {
            assertThat(item.get("eventType")).isEqualTo("USER_LOGIN_SUCCESS");
            assertThat(item.get("subjectUserId")).isEqualTo(adminAId);
        }
    }

    // ---- Scenario 4: pagination -------------------------------------------

    @Test
    @DisplayName("Scenario 4: paging with a small size returns the full set once, no duplication or omission")
    void paginationIsConsistentAcrossPages() {
        int size = 5;
        Response firstPage = queryTenant(tenantAdminTokenA, tenantA, params("page", 0, "size", size));
        long totalElements = total(firstPage);
        int totalPages = firstPage.jsonPath().getInt("totalPages");
        assertThat(totalElements).as("Tenant A has accumulated several events").isGreaterThan(size);
        assertThat(totalPages).as("multiple pages at this size").isGreaterThanOrEqualTo(2);
        assertThat(firstPage.jsonPath().getInt("page")).isZero();
        assertThat(firstPage.jsonPath().getInt("size")).isEqualTo(size);
        assertThat(totalPages).isEqualTo((int) Math.ceil((double) totalElements / size));

        // Page through everything, collecting ids — no duplicates, and the union equals totalElements.
        Set<String> ids = new HashSet<>();
        int collected = 0;
        for (int page = 0; page < totalPages; page++) {
            Response p = queryTenant(tenantAdminTokenA, tenantA, params("page", page, "size", size));
            List<String> pageIds = p.jsonPath().getList("items.id");
            collected += pageIds.size();
            ids.addAll(pageIds);
        }
        assertThat(ids).as("no id appears on two pages").hasSize(collected);
        assertThat((long) ids.size()).as("paging returns exactly the full set").isEqualTo(totalElements);
    }

    // ---- Scenario 5: tenant isolation (the key assertion) -----------------

    @Test
    @DisplayName("Scenario 5: a tenant sees only its own events; cross-tenant access is 403 both directions")
    void tenantIsolationHoldsOnTheQueryLayer() {
        // Tenant A's admin sees A's login and NOT the B-only login — explicit absence, not just "A is present".
        List<String> subjectsA = queryTenant(tenantAdminTokenA, tenantA,
                params("event_type", "USER_LOGIN_SUCCESS", "size", 100)).jsonPath().getList("items.subjectUserId");
        assertThat(subjectsA).as("Tenant A's own login is visible").contains(adminAId);
        assertThat(subjectsA).as("Tenant B's login is NOT visible to Tenant A").doesNotContain(adminBId);

        // Filtering Tenant A's endpoint by the B-user id still returns nothing (query-layer tenant scoping).
        assertThat(total(queryTenant(tenantAdminTokenA, tenantA, params("user_id", adminBId))))
                .as("a B-user id yields no rows on the A endpoint").isZero();

        // Cross-tenant access is refused both directions.
        assertThat(queryTenant(tenantAdminTokenA, tenantB, params()).statusCode())
                .as("A's admin cannot read B's audit log").isEqualTo(403);
        assertThat(queryTenant(tenantAdminTokenB, tenantA, params()).statusCode())
                .as("B's admin cannot read A's audit log").isEqualTo(403);
    }

    // ---- Scenario 6: platform cross-tenant query --------------------------

    @Test
    @DisplayName("Scenario 6: the platform endpoint spans tenants; tenant_id filter narrows; tenant admin is 403")
    void platformCrossTenantQuery() {
        // No tenant_id → both tenants' logins present in the combined result.
        List<String> allSubjects = queryPlatform(platformToken,
                params("event_type", "USER_LOGIN_SUCCESS", "size", 100)).jsonPath().getList("items.subjectUserId");
        assertThat(allSubjects).as("platform query spans tenants").contains(adminAId, adminBId);

        // tenant_id=A → only A's login, not B's.
        List<String> scopedToA = queryPlatform(platformToken,
                params("event_type", "USER_LOGIN_SUCCESS", "tenant_id", tenantA, "size", 100))
                .jsonPath().getList("items.subjectUserId");
        assertThat(scopedToA).as("tenant_id filter includes A").contains(adminAId);
        assertThat(scopedToA).as("tenant_id filter excludes B").doesNotContain(adminBId);

        // A tenant admin can never reach the platform endpoint.
        assertThat(queryPlatform(tenantAdminTokenA, params()).statusCode())
                .as("tenant admin denied on the platform audit endpoint").isEqualTo(403);
    }

    // ---- Scenario 7: bad filter input → RFC 7807 400 ----------------------

    @Test
    @DisplayName("Scenario 7: malformed filters return an RFC 7807 400")
    void badFilterInputIsRejected() {
        Response badType = queryTenant(tenantAdminTokenA, tenantA, params("event_type", "NOT_A_REAL_TYPE"));
        assertThat(badType.statusCode()).isEqualTo(400);
        assertThat(AdminApiClient.problemCode(badType)).isEqualTo("audit.invalid_event_type");

        Response badDate = queryTenant(tenantAdminTokenA, tenantA, params("from", "not-a-date"));
        assertThat(badDate.statusCode()).isEqualTo(400);
        assertThat(AdminApiClient.problemCode(badDate)).isEqualTo("audit.invalid_from");
    }

    // ---- Scenario 8: BONUS — loose, order-independent trail sanity check --

    @Test
    @DisplayName("Scenario 8 (bonus, loose): the platform trail holds a substantial number of events")
    void bonusLooseTrailSubstanceCheck() {
        // Deliberately a LOWER-BOUND sanity check, never an exact count, so it can't become fragile. The bound (20) is
        // chosen to be comfortably below what IT-8's OWN fixtures alone generate (two tenants provisioned + clients +
        // users + logins + suspend/activate/role-assign → well over 20 events), so it holds regardless of JUnit class
        // order — it never depends on other test classes' data. In a full module run it is exceeded many times over.
        long platformTotal = total(queryPlatform(platformToken, params("size", 1)));
        assertThat(platformTotal)
                .as("a substantial real audit trail exists (loose lower bound, order-independent)")
                .isGreaterThan(20);
    }

    // ---- helpers -----------------------------------------------------------

    private static Response queryTenant(String token, String tenantId, Map<String, ?> filters) {
        return adminApi().getQuery(token, "/v1/tenants/{tid}/audit-events", filters, tenantId);
    }

    private static Response queryPlatform(String token, Map<String, ?> filters) {
        return adminApi().getQuery(token, "/v1/platform/audit-events", filters);
    }

    /** Builds a query-param map from key/value pairs, dropping null values. Values may be String or Integer. */
    private static Map<String, String> params(Object... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                map.put((String) kv[i], String.valueOf(kv[i + 1]));
            }
        }
        return map;
    }

    private static Map<String, String> withRange(Map<String, String> base, Instant from, Instant to) {
        Map<String, String> copy = new LinkedHashMap<>(base);
        copy.put("from", from.toString());
        copy.put("to", to.toString());
        return copy;
    }

    private static long total(Response response) {
        return response.jsonPath().getLong("totalElements");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Response response) {
        return response.jsonPath().getList("items");
    }

    private static Map<String, Object> firstItem(Response response) {
        List<Map<String, Object>> items = items(response);
        assertThat(items).as("at least one item").isNotEmpty();
        return items.get(0);
    }

    /** Polls the query until it returns at least one event (audit is async: emit → outbox → 2s drain). */
    private static void awaitPresent(Supplier<Response> query) {
        long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        Response last = null;
        while (System.nanoTime() < deadline) {
            last = query.get();
            if (last.statusCode() == 200 && total(last) >= 1) {
                return;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("audit event did not drain within " + DRAIN_TIMEOUT
                + "; last status=" + (last == null ? "none" : last.statusCode())
                + " body=" + (last == null ? "" : last.asString()));
    }
}
