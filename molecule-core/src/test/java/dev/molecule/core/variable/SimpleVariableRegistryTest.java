package dev.molecule.core.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.molecule.api.variable.Variable;
import dev.molecule.api.variable.VariableContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimpleVariableRegistryTest {

    private static Variable named(String key) {
        return Variable.of(key, "description", context -> Optional.of("value"));
    }

    @Test
    void registersAndFindsVariables() {
        SimpleVariableRegistry registry = new SimpleVariableRegistry();
        registry.register(named("player.name"));

        assertThat(registry.find("player.name")).isPresent();
        assertThat(registry.find("player.missing")).isEmpty();
    }

    @Test
    void refusesToShadowAnExistingVariable() {
        SimpleVariableRegistry registry = new SimpleVariableRegistry();
        registry.register(named("player.name"));

        // Silently overwriting would let load order decide which plugin's value wins,
        // and the symptom would be a wrong value with nothing logged.
        assertThatThrownBy(() -> registry.register(named("player.name")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("molecule.player.name");
    }

    @Test
    void listsVariablesInKeyOrderForThePanel() {
        SimpleVariableRegistry registry = new SimpleVariableRegistry();
        registry.register(named("server.online"));
        registry.register(named("player.name"));
        registry.register(named("world.name"));

        assertThat(registry.all())
                .extracting(Variable::key)
                .containsExactly("player.name", "server.online", "world.name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Player.Name", "player..name", ".name", "player.", "player name", "1player", ""})
    void rejectsKeysThatWouldNotParseInATemplate(String key) {
        assertThatThrownBy(() -> named(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesAgainstTheSuppliedContext() {
        Variable variable = Variable.of("player.name", "Player name", VariableContext::playerName);

        assertThat(variable.resolve(VariableContext.builder().player(UUID.randomUUID(), "Lukas").build()))
                .contains("Lukas");
        assertThat(variable.resolve(VariableContext.empty())).isEmpty();
    }

    @Test
    void contextCarriesArbitraryAttributes() {
        VariableContext context = VariableContext.empty().with("rank", "VIP");

        assertThat(context.attribute("rank")).contains("VIP");
        assertThat(context.attribute("balance")).isEmpty();
    }

    @Test
    void withReturnsACopyAndLeavesTheOriginalAlone() {
        VariableContext original = VariableContext.builder().player(UUID.randomUUID(), "Lukas").build();

        VariableContext extended = original.with("rank", "VIP");

        assertThat(original.attribute("rank")).isEmpty();
        assertThat(extended.attribute("rank")).contains("VIP");
        // The copy keeps what the original carried.
        assertThat(extended.playerName()).contains("Lukas");
    }
}
