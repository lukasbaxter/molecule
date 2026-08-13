package dev.molecule.api.database.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MigrationTest {

    @Test
    void checksumIsStableForTheSameSql() {
        Migration one = new Migration(1, "players", "CREATE TABLE players (id INT)");
        Migration two = new Migration(1, "players", "CREATE TABLE players (id INT)");

        assertThat(one.checksum()).isEqualTo(two.checksum()).hasSize(64);
    }

    @Test
    void checksumIgnoresLineEndingsAndSurroundingWhitespace() {
        // A Windows checkout, or an editor adding a trailing newline, must not read as a
        // schema change — otherwise every contributor trips the drift check.
        Migration unix = new Migration(1, "players", "CREATE TABLE players (\n  id INT\n)");
        Migration windows = new Migration(1, "players", "  CREATE TABLE players (\r\n  id INT\r\n)  ");

        assertThat(unix.checksum()).isEqualTo(windows.checksum());
    }

    @Test
    void checksumChangesWhenTheStatementChanges() {
        Migration original = new Migration(1, "players", "CREATE TABLE players (id INT)");
        Migration altered = new Migration(1, "players", "CREATE TABLE players (id BIGINT)");

        assertThat(original.checksum()).isNotEqualTo(altered.checksum());
    }

    @Test
    void rejectsVersionsThatCannotBeOrdered() {
        assertThatThrownBy(() -> new Migration(0, "nope", "SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Migration(-1, "nope", "SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMigrationsWithNothingToSay() {
        // The description ends up in startup logs and the schema history table; a blank
        // one makes an upgrade impossible to read afterwards.
        assertThatThrownBy(() -> new Migration(1, "  ", "SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Migration(1, "empty", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
