package dev.molecule.api.variable;

import java.util.Collection;
import java.util.Optional;

/**
 * The shared variable registry (SPEC §13).
 *
 * <p>Core registers the variables it can answer itself; every other plugin registers its
 * own. Because the registry is shared, a hologram can show a value owned by the economy
 * plugin without either knowing about the other.
 *
 * <p>Implementations must be safe to read from any thread, since resolution happens on
 * whatever thread is rendering text.
 */
public interface VariableRegistry {

    /**
     * Registers a variable.
     *
     * @param variable the variable to add
     * @throws IllegalStateException if the key is already registered — a silent overwrite
     *     would make one plugin's variable quietly shadow another's
     */
    void register(Variable variable);

    /**
     * Looks up a variable.
     *
     * @param key the dotted key, without the {@code molecule.} prefix
     * @return the variable, or empty if nothing has registered it
     */
    Optional<Variable> find(String key);

    /**
     * Returns every registered variable, for the panel's picker.
     *
     * @return all variables, ordered by key
     */
    Collection<Variable> all();
}
