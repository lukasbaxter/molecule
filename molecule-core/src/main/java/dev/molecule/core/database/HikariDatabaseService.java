package dev.molecule.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.molecule.api.database.DatabaseNamespace;
import dev.molecule.api.database.DatabaseService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import javax.sql.DataSource;

/**
 * The one connection pool in the ecosystem (SPEC §5).
 *
 * <p>Every method schedules its work onto Molecule's own pool, so no database call can
 * occupy a region thread. Callers get a future and decide, explicitly, which thread should
 * receive the result.
 */
public final class HikariDatabaseService implements DatabaseService, AutoCloseable {

    private final HikariDataSource dataSource;
    private final Executor executor;

    /**
     * Opens the pool.
     *
     * @param settings where and how to connect
     * @param executor Molecule's off-thread pool, which all database work runs on
     */
    public HikariDatabaseService(DatabaseSettings settings, Executor executor) {
        this.executor = executor;
        this.dataSource = new HikariDataSource(toHikariConfig(settings));
    }

    private static HikariConfig toHikariConfig(DatabaseSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("molecule-db");
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setConnectionTimeout(settings.connectionTimeout().toMillis());

        // MariaDB 3.x deprecated useSsl in favour of sslMode. "trust" encrypts without
        // verifying the certificate, which is the workable default for the self-hosted
        // databases Molecule targets; an administrator wanting verification sets
        // sslMode explicitly in `properties`, which is applied after this and wins.
        config.addDataSourceProperty("sslMode", settings.useSsl() ? "trust" : "disable");

        for (Map.Entry<String, String> property : settings.properties().entrySet()) {
            config.addDataSourceProperty(property.getKey(), property.getValue());
        }
        return config;
    }

    /**
     * Returns the underlying pool, for the migration runner.
     *
     * <p>Not exposed through {@link DatabaseService}: plugins get namespaced, audited
     * access, not a raw pool.
     *
     * @return the data source
     */
    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public <T> CompletableFuture<List<T>> query(
            DatabaseNamespace namespace,
            String sql,
            StatementBinder binder,
            RowMapper<T> mapper) {

        return CompletableFuture.supplyAsync(
                () -> {
                    try (Connection connection = dataSource.getConnection();
                            PreparedStatement statement = connection.prepareStatement(sql)) {
                        binder.bind(statement);
                        try (ResultSet rows = statement.executeQuery()) {
                            List<T> results = new ArrayList<>();
                            while (rows.next()) {
                                results.add(mapper.map(rows));
                            }
                            return results;
                        }
                    } catch (SQLException e) {
                        throw new CompletionException(failure(namespace, sql, e));
                    }
                },
                executor);
    }

    @Override
    public CompletableFuture<Integer> update(
            DatabaseNamespace namespace, String sql, StatementBinder binder) {

        return CompletableFuture.supplyAsync(
                () -> {
                    try (Connection connection = dataSource.getConnection();
                            PreparedStatement statement = connection.prepareStatement(sql)) {
                        binder.bind(statement);
                        return statement.executeUpdate();
                    } catch (SQLException e) {
                        throw new CompletionException(failure(namespace, sql, e));
                    }
                },
                executor);
    }

    @Override
    public <T> CompletableFuture<T> transaction(
            DatabaseNamespace namespace, TransactionalWork<T> work) {

        return CompletableFuture.supplyAsync(
                () -> {
                    try (Connection connection = dataSource.getConnection()) {
                        boolean autoCommit = connection.getAutoCommit();
                        connection.setAutoCommit(false);
                        try {
                            T result = work.execute(connection);
                            connection.commit();
                            return result;
                        } catch (SQLException | RuntimeException e) {
                            connection.rollback();
                            throw e;
                        } finally {
                            connection.setAutoCommit(autoCommit);
                        }
                    } catch (SQLException e) {
                        throw new CompletionException(
                                new SQLException(
                                        "Transaction failed for " + namespace + ": " + e.getMessage(),
                                        e));
                    }
                },
                executor);
    }

    @Override
    public boolean isHealthy() {
        return dataSource.isRunning() && !dataSource.isClosed();
    }

    @Override
    public void close() {
        dataSource.close();
    }

    /**
     * Builds a failure that names the namespace responsible.
     *
     * <p>With every plugin sharing one pool, "a query failed" is not actionable — the
     * message has to say whose query it was.
     */
    private static SQLException failure(DatabaseNamespace namespace, String sql, SQLException cause) {
        return new SQLException(
                "Query failed for " + namespace + ": " + sql + " (" + cause.getMessage() + ")", cause);
    }
}
