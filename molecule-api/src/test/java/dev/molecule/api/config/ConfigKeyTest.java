package dev.molecule.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.molecule.api.database.DatabaseNamespace;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigKeyTest {

    private static final DatabaseNamespace TP = DatabaseNamespace.forModule("tp");

    @Test
    void roundTripsEachSupportedType() {
        assertThat(ConfigKey.integer(TP, "rtp.radius", 10_000).parse("25000")).isEqualTo(25_000);
        assertThat(ConfigKey.longValue(TP, "rtp.seed", 1L).parse("9999999999")).isEqualTo(9_999_999_999L);
        assertThat(ConfigKey.decimal(TP, "xp.multiplier", 1.0).parse("0.75")).isEqualTo(0.75);
        assertThat(ConfigKey.string(TP, "messages.prefix", "[TP]").parse("[Molecule]"))
                .isEqualTo("[Molecule]");
    }

    @Test
    void listsRoundTripThroughTheirStoredForm() {
        ConfigKey<List<String>> key = ConfigKey.stringList(TP, "rtp.worlds", List.of("world"));

        String stored = key.serialise(List.of("world", "world_nether"));

        assertThat(key.parse(stored)).containsExactly("world", "world_nether");
    }

    @Test
    void emptyListRoundTrips() {
        ConfigKey<List<String>> key = ConfigKey.stringList(TP, "rtp.worlds", List.of());

        assertThat(key.parse(key.serialise(List.of()))).isEmpty();
    }

    /**
     * {@code Boolean.parseBoolean} treats every unrecognised string as false, so a typo in
     * the panel would silently disable a feature. These spellings are accepted and
     * anything else is rejected instead.
     */
    @ParameterizedTest
    @CsvSource({
        "true,true", "TRUE,true", "yes,true", "on,true", "1,true",
        "false,false", "no,false", "off,false", "0,false",
    })
    void acceptsTheBooleanSpellingsAdministratorsActuallyType(String raw, boolean expected) {
        assertThat(ConfigKey.bool(TP, "rtp.enabled", false).parse(raw)).isEqualTo(expected);
    }

    @Test
    void rejectsAnythingElseAsABoolean() {
        ConfigKey<Boolean> key = ConfigKey.bool(TP, "rtp.enabled", false);

        assertThatThrownBy(() -> key.parse("maybe")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAStoredValueOfTheWrongType() {
        ConfigKey<Integer> key = ConfigKey.integer(TP, "rtp.radius", 10_000);

        assertThatThrownBy(() -> key.parse("not a number"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid Integer");
    }

    @Test
    void enforcesConstraintsOnBothReadAndWrite() {
        ConfigKey<Integer> radius =
                ConfigKey.integer(TP, "rtp.radius", 10_000)
                        .constrained(value -> value >= 100 && value <= 30_000_000, "between 100 and 30000000");

        assertThat(radius.validate(25_000)).isEqualTo(25_000);
        // Rejected on the way in from the panel...
        assertThatThrownBy(() -> radius.validate(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 100 and 30000000");
        // ...and on the way out of the database, so a hand-edited row cannot poison it.
        assertThatThrownBy(() -> radius.parse("5")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesADefaultThatBreaksItsOwnConstraint() {
        // Otherwise the setting is unusable until someone changes it, and the panel shows
        // a value it would reject if you retyped it.
        assertThatThrownBy(
                        () ->
                                ConfigKey.integer(TP, "rtp.radius", 10)
                                        .constrained(value -> value >= 100, "at least 100"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not satisfy its own constraint");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Rtp.Radius", "rtp..radius", ".radius", "rtp.", "rtp radius", "1rtp", ""})
    void rejectsPathsThatArePlaceholdersForMistakes(String path) {
        assertThatThrownBy(() -> ConfigKey.integer(TP, path, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysAreIdentifiedByNamespaceAndPath() {
        // The service caches by identity, so two declarations of the same setting must be
        // the same key even when their defaults differ.
        assertThat(ConfigKey.integer(TP, "rtp.radius", 1))
                .isEqualTo(ConfigKey.integer(TP, "rtp.radius", 2))
                .hasSameHashCodeAs(ConfigKey.integer(TP, "rtp.radius", 2));

        assertThat(ConfigKey.integer(TP, "rtp.radius", 1))
                .isNotEqualTo(ConfigKey.integer(DatabaseNamespace.forModule("eco"), "rtp.radius", 1));
    }
}
