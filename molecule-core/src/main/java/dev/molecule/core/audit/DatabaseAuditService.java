package dev.molecule.core.audit;

import dev.molecule.api.audit.AuditActor;
import dev.molecule.api.audit.AuditEntry;
import dev.molecule.api.audit.AuditRecord;
import dev.molecule.api.audit.AuditService;
import dev.molecule.api.audit.ChangeSource;
import dev.molecule.api.database.DatabaseService;
import dev.molecule.core.database.CoreSchema;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stores audit history in MariaDB (SPEC §6).
 *
 * <p>Insert and select only. There is no update or delete path, deliberately — the absence
 * is the guarantee that history cannot be rewritten.
 */
public final class DatabaseAuditService implements AuditService {

    private final DatabaseService database;

    /**
     * Creates the service.
     *
     * @param database the shared database service
     */
    public DatabaseAuditService(DatabaseService database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Long> record(AuditRecord entry) {
        return database.transaction(
                CoreSchema.NAMESPACE,
                connection -> {
                    String sql =
                            "INSERT INTO "
                                    + CoreSchema.NAMESPACE.table("audit")
                                    + " (actor_uuid, actor_name, actor_type, source, namespace,"
                                    + " operation, target, old_value, new_value, undoes_revision,"
                                    + " context)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    try (var statement =
                            connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                        statement.setBytes(1, entry.actor().uuid().map(DatabaseAuditService::toBytes).orElse(null));
                        statement.setString(2, entry.actor().name());
                        statement.setString(3, entry.actor().type().name());
                        statement.setString(4, entry.source().name());
                        statement.setString(5, entry.namespace());
                        statement.setString(6, entry.operation());
                        statement.setString(7, entry.target().orElse(null));
                        statement.setString(8, entry.oldValue().orElse(null));
                        statement.setString(9, entry.newValue().orElse(null));
                        if (entry.undoesRevision().isPresent()) {
                            statement.setLong(10, entry.undoesRevision().getAsLong());
                        } else {
                            statement.setNull(10, java.sql.Types.BIGINT);
                        }
                        statement.setString(11, entry.context().orElse(null));
                        statement.executeUpdate();

                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new SQLException("Audit insert returned no revision number");
                            }
                            return keys.getLong(1);
                        }
                    }
                });
    }

    @Override
    public CompletableFuture<List<AuditEntry>> search(AuditQuery query) {
        StringBuilder sql =
                new StringBuilder("SELECT * FROM " + CoreSchema.NAMESPACE.table("audit") + " WHERE 1=1");
        List<String> parameters = new ArrayList<>();
        if (query.namespace().isPresent()) {
            sql.append(" AND namespace = ?");
            parameters.add(query.namespace().get());
        }
        if (query.target().isPresent()) {
            sql.append(" AND target = ?");
            parameters.add(query.target().get());
        }
        // Newest first: history is read backwards from now, and the limit should cut off
        // the oldest entries rather than the most recent ones.
        sql.append(" ORDER BY revision DESC LIMIT ").append(query.limit());

        return database.query(
                CoreSchema.NAMESPACE,
                sql.toString(),
                statement -> {
                    for (int i = 0; i < parameters.size(); i++) {
                        statement.setString(i + 1, parameters.get(i));
                    }
                },
                DatabaseAuditService::readEntry);
    }

    @Override
    public CompletableFuture<Optional<AuditEntry>> find(long revision) {
        return database
                .query(
                        CoreSchema.NAMESPACE,
                        "SELECT * FROM " + CoreSchema.NAMESPACE.table("audit") + " WHERE revision = ?",
                        statement -> statement.setLong(1, revision),
                        DatabaseAuditService::readEntry)
                .thenApply(rows -> rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0)));
    }

    private static AuditEntry readEntry(ResultSet row) throws SQLException {
        byte[] actorUuid = row.getBytes("actor_uuid");
        long undoes = row.getLong("undoes_revision");
        boolean undoesWasNull = row.wasNull();

        return new AuditEntry(
                row.getLong("revision"),
                row.getTimestamp("occurred_at").toInstant(),
                new AuditActor(
                        Optional.ofNullable(actorUuid).map(DatabaseAuditService::toUuid),
                        row.getString("actor_name"),
                        AuditActor.ActorType.valueOf(row.getString("actor_type"))),
                ChangeSource.valueOf(row.getString("source")),
                row.getString("namespace"),
                row.getString("operation"),
                Optional.ofNullable(row.getString("target")),
                Optional.ofNullable(row.getString("old_value")),
                Optional.ofNullable(row.getString("new_value")),
                undoesWasNull ? OptionalLong.empty() : OptionalLong.of(undoes),
                Optional.ofNullable(row.getString("context")));
    }

    /**
     * Packs a UUID into 16 bytes.
     *
     * <p>BINARY(16) rather than CHAR(36): a third of the storage, and a third of the index,
     * on a table designed to grow forever.
     */
    private static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
