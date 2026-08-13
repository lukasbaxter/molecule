package dev.molecule.api.text;

import dev.molecule.api.variable.VariableContext;
import net.kyori.adventure.text.Component;

/**
 * The universal text engine (SPEC §14).
 *
 * <p>One way to turn an administrator's template into text, reused everywhere in Molecule
 * so a gradient written for a hologram behaves identically in a scoreboard or an MOTD.
 *
 * <p>Templates are MiniMessage, so colours, gradients, fonts and the usual decorations all
 * work, plus Molecule variables written as {@code <molecule.player.name>}.
 *
 * <h2>Variable values are never markup</h2>
 *
 * <p>Resolved values are inserted as literal text, not parsed. A player whose name is
 * {@code <red>} shows those characters rather than turning the rest of the line red, and a
 * value cannot introduce a click or hover action. Only the template itself — written by an
 * administrator — is treated as markup.
 */
public interface TextRenderer {

    /**
     * Renders a template to a component.
     *
     * @param template the MiniMessage template, which may contain Molecule variables
     * @param context  what to resolve variables against
     * @return the rendered component
     */
    Component render(String template, VariableContext context);

    /**
     * Renders a template and strips all formatting.
     *
     * <p>For places that cannot carry colour — console output, log lines, and the plain
     * text half of a server list response.
     *
     * @param template the MiniMessage template
     * @param context  what to resolve variables against
     * @return the rendered text, without formatting
     */
    String renderPlain(String template, VariableContext context);
}
