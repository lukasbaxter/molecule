package dev.molecule.core.text;

import static org.assertj.core.api.Assertions.assertThat;

import dev.molecule.api.variable.Variable;
import dev.molecule.api.variable.VariableContext;
import dev.molecule.core.variable.SimpleVariableRegistry;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MiniMessageTextRendererTest {

    private SimpleVariableRegistry registry;
    private MiniMessageTextRenderer renderer;

    @BeforeEach
    void setUp() {
        registry = new SimpleVariableRegistry();
        registry.register(Variable.of("player.name", "Player name", VariableContext::playerName));
        registry.register(
                Variable.of("server.online", "Online count", context -> Optional.of("42")));
        renderer = new MiniMessageTextRenderer(registry);
    }

    private static VariableContext withPlayer(String name) {
        return VariableContext.builder().player(UUID.randomUUID(), name).build();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void substitutesVariablesWrittenInTheSpecsSyntax() {
        String result =
                renderer.renderPlain(
                        "Welcome <molecule.player.name>! <molecule.server.online> online.",
                        withPlayer("Lukas"));

        assertThat(result).isEqualTo("Welcome Lukas! 42 online.");
    }

    @Test
    void stillSupportsOrdinaryMiniMessageFormatting() {
        Component result = renderer.render("<red><bold>Danger", VariableContext.empty());

        assertThat(result.color()).isEqualTo(NamedTextColor.RED);
        assertThat(result.decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.TRUE);
    }

    @Test
    void variablesWorkInsideFormatting() {
        Component result = renderer.render("<gradient:#ff0000:#0000ff><molecule.player.name>", withPlayer("Lukas"));

        assertThat(plain(result)).isEqualTo("Lukas");
    }

    /**
     * The reason values go through a tag resolver rather than string substitution. A player
     * named {@code <red>} must not be able to recolour the rest of a scoreboard line.
     */
    @Test
    void aValueThatLooksLikeMarkupIsShownLiterally() {
        Component result = renderer.render("<molecule.player.name>: hello", withPlayer("<red>"));

        assertThat(plain(result)).isEqualTo("<red>: hello");
        // The trailing text must not have picked up a colour from the injected tag.
        assertThat(result.color()).isNull();
    }

    /** The same protection, against the more damaging case: an injected click action. */
    @Test
    void aValueCannotIntroduceAClickAction() {
        Component result =
                renderer.render(
                        "<molecule.player.name> joined",
                        withPlayer("<click:run_command:'/op evil'>click me</click>"));

        assertThat(plain(result)).isEqualTo("<click:run_command:'/op evil'>click me</click> joined");
        assertThat(result.clickEvent()).isNull();
        assertThat(result.children()).allSatisfy(child -> assertThat(child.clickEvent()).isNull());
    }

    @Test
    void anUnknownVariableIsLeftVisibleSoTyposAreObvious() {
        String result = renderer.renderPlain("Hello <molecule.player.nmae>", withPlayer("Lukas"));

        assertThat(result).isEqualTo("Hello <molecule.player.nmae>");
    }

    @Test
    void aVariableThatDoesNotApplyRendersAsNothing() {
        // No player in context: a scoreboard should show a blank, not a raw tag.
        String result = renderer.renderPlain("Name: <molecule.player.name>", VariableContext.empty());

        assertThat(result).isEqualTo("Name: ");
    }

    @Test
    void anEscapedVariableIsLeftAlone() {
        // An administrator documenting the syntax should get the syntax.
        String result = renderer.renderPlain("Use \\<molecule.player.name>", withPlayer("Lukas"));

        assertThat(result).isEqualTo("Use <molecule.player.name>");
    }

    @Test
    void repeatedVariablesAllResolve() {
        String result =
                renderer.renderPlain(
                        "<molecule.player.name> <molecule.player.name>", withPlayer("Lukas"));

        assertThat(result).isEqualTo("Lukas Lukas");
    }

    @Test
    void plainRenderingStripsFormatting() {
        String result = renderer.renderPlain("<red><bold>Hi <molecule.player.name>", withPlayer("Lukas"));

        assertThat(result).isEqualTo("Hi Lukas");
    }

    @Test
    void templateWithNoVariablesIsUnchanged() {
        assertThat(renderer.renderPlain("just text", VariableContext.empty())).isEqualTo("just text");
    }
}
