package dev.molecule.api.audit;

import java.util.Optional;
import java.util.UUID;

/**
 * Who made a change (SPEC §6).
 *
 * <p>The name is stored alongside the id rather than joined at read time, so history stays
 * readable after a player renames or is deleted — an audit log that cannot say who did
 * something is not an audit log.
 *
 * @param uuid the actor's Minecraft UUID, absent for console and system actors
 * @param name display name at the time of the change
 * @param type what kind of actor this is
 */
public record AuditActor(Optional<UUID> uuid, String name, ActorType type) {

    /** What kind of actor made a change. */
    public enum ActorType {
        /** A player, in game or signed into the panel. */
        PLAYER,
        /** The server console. */
        CONSOLE,
        /** Molecule itself. */
        SYSTEM,
        /** A third-party plugin. */
        PLUGIN
    }

    /**
     * Validates the actor.
     *
     * @throws IllegalArgumentException if the name is blank
     */
    public AuditActor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Audit actor needs a name");
        }
    }

    /**
     * Creates a player actor.
     *
     * @param uuid the player's UUID
     * @param name the player's name at the time
     * @return the actor
     */
    public static AuditActor player(UUID uuid, String name) {
        return new AuditActor(Optional.of(uuid), name, ActorType.PLAYER);
    }

    /**
     * Creates the console actor.
     *
     * @return the actor
     */
    public static AuditActor console() {
        return new AuditActor(Optional.empty(), "CONSOLE", ActorType.CONSOLE);
    }

    /**
     * Creates the actor Molecule uses for its own changes.
     *
     * @return the actor
     */
    public static AuditActor system() {
        return new AuditActor(Optional.empty(), "Molecule", ActorType.SYSTEM);
    }

    /**
     * Creates an actor representing a third-party plugin.
     *
     * @param pluginName the plugin's name
     * @return the actor
     */
    public static AuditActor plugin(String pluginName) {
        return new AuditActor(Optional.empty(), pluginName, ActorType.PLUGIN);
    }
}
