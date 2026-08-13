package dev.molecule.core.text;

import dev.molecule.api.text.TextRenderer;
import dev.molecule.api.variable.Variable;
import dev.molecule.api.variable.VariableContext;
import dev.molecule.api.variable.VariableRegistry;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * The MiniMessage-backed text engine (SPEC §14).
 *
 * <h2>Why variables are rewritten before parsing</h2>
 *
 * <p>The spec writes variables as {@code <molecule.player.name>}, but MiniMessage tag names
 * match {@code [!?#]?[a-z0-9_-]*} — dots are not allowed, so that is not a tag it can
 * parse. Each occurrence is therefore rewritten to {@code <molecule:'player.name'>}, which
 * is a single {@code molecule} tag carrying the path as an argument, before the template
 * reaches MiniMessage.
 *
 * <p>The rewrite happens on the template only. Resolved values are supplied through a
 * {@link TagResolver} as literal text, so a value can never introduce markup — see
 * {@link TextRenderer} for what that prevents.
 */
public final class MiniMessageTextRenderer implements TextRenderer {

    /** The tag name variables are folded into. */
    private static final String VARIABLE_TAG = "molecule";

    /**
     * Matches {@code <molecule.some.path>}, but not an escaped {@code \<molecule.x>} — an
     * administrator writing the literal tag should get the literal tag.
     */
    private static final Pattern VARIABLE_REFERENCE =
            Pattern.compile("(?<!\\\\)<molecule\\.([a-z0-9_.]+)>");

    private final VariableRegistry variables;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /**
     * Creates the renderer.
     *
     * @param variables where variables are looked up
     */
    public MiniMessageTextRenderer(VariableRegistry variables) {
        this.variables = variables;
    }

    @Override
    public Component render(String template, VariableContext context) {
        return miniMessage.deserialize(rewriteVariables(template), variableResolver(context));
    }

    @Override
    public String renderPlain(String template, VariableContext context) {
        return PlainTextComponentSerializer.plainText().serialize(render(template, context));
    }

    /**
     * Rewrites {@code <molecule.path>} into {@code <molecule:'path'>}.
     *
     * <p>The captured path is restricted to {@code [a-z0-9_.]} by the pattern, so it cannot
     * contain a quote and cannot break out of the argument it is placed in.
     */
    private static String rewriteVariables(String template) {
        Matcher matcher = VARIABLE_REFERENCE.matcher(template);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                    rewritten, Matcher.quoteReplacement("<" + VARIABLE_TAG + ":'" + matcher.group(1) + "'>"));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    /**
     * Resolves the {@code molecule} tag.
     *
     * <p>Three outcomes, each chosen so an administrator can tell what happened:
     *
     * <ul>
     *   <li>registered and applicable — the value, inserted as literal text
     *   <li>registered but not applicable here — nothing, since a scoreboard line should
     *       not show a raw tag just because there is no player
     *   <li>not registered at all — the original text, so a typo is visible rather than
     *       silently blank
     * </ul>
     */
    private TagResolver variableResolver(VariableContext context) {
        return TagResolver.resolver(
                VARIABLE_TAG,
                (arguments, ignored) -> {
                    String key = arguments.popOr("Molecule variable needs a name").value();
                    Optional<Variable> variable = variables.find(key);
                    if (variable.isEmpty()) {
                        return Tag.selfClosingInserting(Component.text("<molecule." + key + ">"));
                    }
                    String value = variable.get().resolve(context).orElse("");
                    return Tag.selfClosingInserting(Component.text(value));
                });
    }
}
