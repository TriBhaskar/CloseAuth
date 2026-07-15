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
 * A minimal, <b>read-only</b> JDBC helper for direct database assertions against the running Postgres container — just
 * enough to keep raw SQL out of every test method, deliberately NOT a repository/ORM layer (this module owns no
 * entities). A journey uses it to prove a side effect really landed in the DB, not merely that the API claimed success.
 *
 * <p>Each call opens and closes its own short-lived connection (test volumes are tiny; pooling would be premature).
 * Only {@code SELECT} is ever issued here — this module never mutates the database.
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
}
