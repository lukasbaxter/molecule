package dev.molecule.api.variable;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A named value that can appear in any Molecule text (SPEC §13).
 *
 * <p>Written in templates as {@code <molecule.player.name>}, and usable everywhere text is
 * — UI, NPCs, holograms, scoreboards, tab, MOTD, messages, conditions, actions, rank
 * formatting and cosmetics.
 *
 * <h2>Resolution must not block</h2>
 *
 * <p>Variables are resolved on every scoreboard tick, hologram refresh and nameplate
 * update. A resolver must therefore answer from memory — a cache, a snapshot, a counter —
 * and never query a database, take a lock, or read live entity state. A variable that
 * needs stored data should read it from its plugin's own cache, kept up to date
 * separately.
 */
public interface Variable {

    /** Same shape as a config path, so both read consistently in the panel. */
    Pattern VALID_KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");

    /**
     * Returns the dotted key, without the {@code molecule.} prefix.
     *
     * @return the key, such as {@code player.name}
     */
    String key();

    /**
     * Returns a short description, shown in the panel's variable picker.
     *
     * @return the description
     */
    String description();

    /**
     * Resolves this variable.
     *
     * @param context what to resolve against
     * @return the value, or empty if this variable does not apply to the given context —
     *     a player variable resolved with no player, for instance
     */
    Optional<String> resolve(VariableContext context);

    /**
     * Creates a variable from a function.
     *
     * @param key         the dotted key, without the {@code molecule.} prefix
     * @param description a short description for the panel
     * @param resolver    computes the value; must not block
     * @return the variable
     * @throws IllegalArgumentException if the key is not a legal dotted identifier
     */
    static Variable of(
            String key, String description, Function<VariableContext, Optional<String>> resolver) {

        if (!VALID_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Invalid variable key '"
                            + key
                            + "': expected dotted lowercase segments, such as 'player.name'");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Variable " + key + " needs a description");
        }

        record FunctionVariable(
                String key, String description, Function<VariableContext, Optional<String>> resolver)
                implements Variable {
            @Override
            public Optional<String> resolve(VariableContext context) {
                return resolver.apply(context);
            }
        }
        return new FunctionVariable(key, description, resolver);
    }
}
