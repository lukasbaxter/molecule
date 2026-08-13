package dev.molecule.core.database;

import java.time.Duration;
import java.util.Map;

/**
 * How to reach the Molecule database (SPEC §5).
 *
 * <p>These are bootstrap settings, so they come from YAML rather than from the database —
 * they are what Molecule needs before it can read anything. Everything else lives in
 * MariaDB, which stays the single source of truth for live configuration (SPEC §4).
 *
 * @param host              database host
 * @param port              database port
 * @param database          schema name
 * @param username          user to connect as
 * @param password          password for that user
 * @param useSsl            whether to encrypt the connection; maps to MariaDB's
 *     {@code sslMode} of {@code trust} or {@code disable}. Set {@code sslMode} in
 *     {@code properties} to require certificate verification instead.
 * @param poolSize          maximum pooled connections
 * @param connectionTimeout how long to wait for a connection before failing
 * @param properties        extra JDBC properties, passed through verbatim and applied
 *     after the derived defaults, so they can override them
 */
public record DatabaseSettings(
        String host,
        int port,
        String database,
        String username,
        String password,
        boolean useSsl,
        int poolSize,
        Duration connectionTimeout,
        Map<String, String> properties) {

    /**
     * Validates and normalises settings.
     *
     * @throws IllegalArgumentException if a value would produce an unusable pool
     */
    public DatabaseSettings {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Database host is required");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Database port out of range: " + port);
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("Database name is required");
        }
        if (poolSize < 1) {
            throw new IllegalArgumentException("Pool size must be at least 1, got " + poolSize);
        }
        properties = Map.copyOf(properties);
    }

    /**
     * Builds the JDBC URL for these settings.
     *
     * <p>Credentials are passed to the pool separately rather than embedded here, so a URL
     * appearing in a log or an exception never carries a password.
     *
     * @return the JDBC URL
     */
    public String jdbcUrl() {
        return "jdbc:mariadb://" + host + ":" + port + "/" + database;
    }

    /**
     * Returns a copy of these settings with the password blanked.
     *
     * <p>Use this anywhere settings are logged or exposed through the web panel.
     *
     * @return redacted settings
     */
    public DatabaseSettings redacted() {
        return new DatabaseSettings(
                host, port, database, username, "****", useSsl, poolSize, connectionTimeout, properties);
    }

    @Override
    public String toString() {
        // Records generate a toString that would print the password; override it so an
        // accidental log line cannot leak credentials.
        return "DatabaseSettings["
                + "host="
                + host
                + ", port="
                + port
                + ", database="
                + database
                + ", username="
                + username
                + ", password=****"
                + ", useSsl="
                + useSsl
                + ", poolSize="
                + poolSize
                + ", connectionTimeout="
                + connectionTimeout
                + ']';
    }
}
