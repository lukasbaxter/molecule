package dev.molecule.core.variable;

import dev.molecule.api.variable.Variable;
import dev.molecule.api.variable.VariableRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The shared variable registry (SPEC §13).
 *
 * <p>A concurrent sorted map: reads happen from whatever thread is rendering text, and the
 * ordering means the panel's variable picker lists keys alphabetically without sorting on
 * every request.
 */
public final class SimpleVariableRegistry implements VariableRegistry {

    private final Map<String, Variable> variables = new ConcurrentSkipListMap<>();

    @Override
    public void register(Variable variable) {
        Variable existing = variables.putIfAbsent(variable.key(), variable);
        if (existing != null) {
            // Overwriting would let a late-loading plugin silently shadow another's
            // variable, and the symptom would be a wrong value with no error anywhere.
            throw new IllegalStateException(
                    "Variable 'molecule." + variable.key() + "' is already registered");
        }
    }

    @Override
    public Optional<Variable> find(String key) {
        return Optional.ofNullable(variables.get(key));
    }

    @Override
    public Collection<Variable> all() {
        return List.copyOf(variables.values());
    }
}
