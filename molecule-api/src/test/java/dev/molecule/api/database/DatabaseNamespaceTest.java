package dev.molecule.api.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseNamespaceTest {

    @Test
    void prefixesTablesWithTheModuleName() {
        DatabaseNamespace namespace = DatabaseNamespace.forModule("tp");

        assertThat(namespace.table("homes")).isEqualTo("molecule_tp_homes");
        assertThat(namespace.prefix()).isEqualTo("molecule_tp_");
    }

    @Test
    void acceptsTheFullModuleNameForConvenience() {
        // Callers usually have the Gradle module name to hand, not the bare suffix.
        assertThat(DatabaseNamespace.forModule("molecule-npc").table("npcs"))
                .isEqualTo("molecule_npc_npcs");
    }

    @Test
    void normalisesCase() {
        assertThat(DatabaseNamespace.forModule("Eco").table("Accounts"))
                .isEqualTo("molecule_eco_accounts");
    }

    /**
     * Table names become SQL identifiers, which cannot be parameterised. The only defence
     * is refusing anything that is not a plain identifier, so these must all be rejected
     * rather than escaped.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "homes; DROP TABLE users",
                "homes`",
                "homes--",
                "ho mes",
                "homes'",
                "\"homes\"",
                "1homes",
                "_homes",
                "",
            })
    void rejectsAnythingThatIsNotAPlainIdentifier(String hostile) {
        DatabaseNamespace namespace = DatabaseNamespace.forModule("tp");

        assertThatThrownBy(() -> namespace.table(hostile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsModuleNamesThatAreNotIdentifiers() {
        assertThatThrownBy(() -> DatabaseNamespace.forModule("tp; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNamesTheDatabaseWouldTruncate() {
        DatabaseNamespace namespace = DatabaseNamespace.forModule("tp");

        // Silent truncation at 64 characters would collide two different tables.
        assertThatThrownBy(() -> namespace.table("a".repeat(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }
}
