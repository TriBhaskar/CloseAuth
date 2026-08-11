package com.anterka.closeauth.it.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A minimal JDBC helper for direct database assertions against the running Postgres container — just enough to keep
 * raw SQL out of every test method, deliberately NOT a repository/ORM layer (this module owns no entities). A
 * journey uses it to prove a side effect really landed in the DB, not merely that the API claimed success.
 *
 * <p>Each call opens and closes its own short-lived connection (test volumes are tiny; pooling would be premature).
 *
 * <p><b>{@link #execute} is a deliberate, narrow exception to "queries only."</b> {@code TempCredentialRotationJourneyTest}
 * (Phase 2 of the tenant-onboarding design) deliberately sets {@code user_identities.must_change_password} directly
 * via SQL rather than through {@code TenantAdminBootstrapJourneyTest}'s (Phase 3) issuance endpoints — it exists to
 * prove the rotation GATE in isolation, against a row constructed independently of any one issuance path, so the
 * two test classes don't duplicate coverage of each other's concern. This is not a general-purpose write escape
 * hatch — every other journey in this module stays read-only through {@link #queryOne}.
 */
public final class Db {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public Db(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Runs a parameterized {@code SELECT} and returns the first row as a column→value map (column names lower-cased),
     * or empty if there were no rows.
     */
    public Optional<Map<String, Object>> queryOne(String sql, Object... params) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ResultSetMetaData meta = rs.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int col = 1; col <= meta.getColumnCount(); col++) {
                    row.put(meta.getColumnLabel(col).toLowerCase(), rs.getObject(col));
                }
                return Optional.of(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Read-only DB query failed: " + sql, e);
        }
    }

    /**
     * Runs a parameterized DML statement (UPDATE/INSERT/DELETE) and returns the affected row count. See the class
     * javadoc — this exists ONLY because Phase 2's fixtures need to set {@code must_change_password} /
     * {@code temp_credential_expires_at} directly; no product code sets them yet. Not for general use.
     */
    public int execute(String sql, Object... params) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("DB write failed: " + sql, e);
        }
    }
}
