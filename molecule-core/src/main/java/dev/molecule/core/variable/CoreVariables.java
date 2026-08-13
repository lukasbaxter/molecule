package dev.molecule.core.variable;

import dev.molecule.api.position.PositionService;
import dev.molecule.api.variable.Variable;
import dev.molecule.api.variable.VariableRegistry;
import java.util.Optional;
import org.bukkit.Bukkit;

/**
 * The variables Core can answer on its own (SPEC §13).
 *
 * <p>Everything here resolves from memory. Player identity comes from the resolution
 * context, which was built on a thread owning that player, and the online count comes from
 * Core's position snapshots rather than from a live player-list read.
 *
 * <p>One caveat worth stating plainly: Folia ticks regions independently, so there is no
 * single authoritative tick rate. {@code server.tps} reports the server-wide figure, which
 * is an aggregate rather than the tick rate of the region the viewing player is in. A
 * per-region variable would be more truthful for a scoreboard and is worth adding once
 * Molecule tracks regions itself.
 */
public final class CoreVariables {

    private CoreVariables() {}

    /**
     * Registers Core's variables.
     *
     * @param registry  where to register them
     * @param positions supplies the online count without touching live server state
     */
    public static void registerAll(VariableRegistry registry, PositionService positions) {
        registry.register(
                Variable.of(
                        "player.name",
                        "The player's name",
                        context -> context.playerName()));

        registry.register(
                Variable.of(
                        "player.uuid",
                        "The player's unique id",
                        context -> context.playerId().map(Object::toString)));

        registry.register(
                Variable.of(
                        "world.name",
                        "The world the player is in",
                        context -> context.worldName()));

        registry.register(
                Variable.of(
                        "server.online",
                        "How many players are online",
                        context -> Optional.of(String.valueOf(positions.trackedCount()))));

        registry.register(
                Variable.of(
                        "server.tps",
                        "Server-wide ticks per second, averaged over one minute",
                        context -> Optional.of(String.format("%.2f", Bukkit.getTPS()[0]))));
    }
}
