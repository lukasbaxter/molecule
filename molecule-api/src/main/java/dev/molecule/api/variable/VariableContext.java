package dev.molecule.api.variable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The subject a variable is resolved against (SPEC §13).
 *
 * <p>Immutable, and built from values already read on a legal thread. This is deliberate:
 * variables are resolved by scoreboards, holograms, NPC nameplates and tab lists, all of
 * which run off the server threads. Passing a live {@code Player} into that machinery
 * would invite exactly the off-thread entity read SPEC §1A forbids, so the context carries
 * a snapshot instead.
 *
 * <pre>{@code
 * VariableContext context = VariableContext.builder()
 *         .player(uuid, "Lukas")
 *         .world(worldId, "world")
 *         .attribute("rank", "VIP")
 *         .build();
 * }</pre>
 */
public final class VariableContext {

    private static final VariableContext EMPTY = builder().build();

    private final Optional<UUID> playerId;
    private final Optional<String> playerName;
    private final Optional<UUID> worldId;
    private final Optional<String> worldName;
    private final Map<String, String> attributes;

    private VariableContext(Builder builder) {
        this.playerId = Optional.ofNullable(builder.playerId);
        this.playerName = Optional.ofNullable(builder.playerName);
        this.worldId = Optional.ofNullable(builder.worldId);
        this.worldName = Optional.ofNullable(builder.worldName);
        this.attributes = Map.copyOf(builder.attributes);
    }

    /**
     * Returns a context with no subject, for text that mentions only server-wide values.
     *
     * @return the empty context
     */
    public static VariableContext empty() {
        return EMPTY;
    }

    /**
     * Starts building a context.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the subject player's id.
     *
     * @return the id, or empty if this context has no player
     */
    public Optional<UUID> playerId() {
        return playerId;
    }

    /**
     * Returns the subject player's name.
     *
     * @return the name, or empty if this context has no player
     */
    public Optional<String> playerName() {
        return playerName;
    }

    /**
     * Returns the subject world's id.
     *
     * @return the id, or empty if this context has no world
     */
    public Optional<UUID> worldId() {
        return worldId;
    }

    /**
     * Returns the subject world's name.
     *
     * @return the name, or empty if this context has no world
     */
    public Optional<String> worldName() {
        return worldName;
    }

    /**
     * Reads an extra value supplied by whoever built this context.
     *
     * <p>Lets a caller pass through what it already knows — a rank, a balance, a shop name
     * — so a variable does not have to look up something the caller had in hand.
     *
     * @param name the attribute name
     * @return the value, or empty if not supplied
     */
    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    /**
     * Returns a copy of this context with one extra attribute.
     *
     * @param name  the attribute name
     * @param value the value
     * @return the new context
     */
    public VariableContext with(String name, String value) {
        Builder builder = toBuilder();
        builder.attribute(name, value);
        return builder.build();
    }

    private Builder toBuilder() {
        Builder builder = new Builder();
        playerId.ifPresent(id -> builder.playerId = id);
        playerName.ifPresent(name -> builder.playerName = name);
        worldId.ifPresent(id -> builder.worldId = id);
        worldName.ifPresent(name -> builder.worldName = name);
        builder.attributes.putAll(attributes);
        return builder;
    }

    /** Builds a {@link VariableContext}. */
    public static final class Builder {

        private UUID playerId;
        private String playerName;
        private UUID worldId;
        private String worldName;
        private final Map<String, String> attributes = new HashMap<>();

        private Builder() {}

        /**
         * Sets the subject player.
         *
         * @param id   the player's id
         * @param name the player's name, read on a thread owning them
         * @return this builder
         */
        public Builder player(UUID id, String name) {
            this.playerId = id;
            this.playerName = name;
            return this;
        }

        /**
         * Sets the subject world.
         *
         * @param id   the world's id
         * @param name the world's name
         * @return this builder
         */
        public Builder world(UUID id, String name) {
            this.worldId = id;
            this.worldName = name;
            return this;
        }

        /**
         * Adds an extra value.
         *
         * @param name  the attribute name
         * @param value the value
         * @return this builder
         */
        public Builder attribute(String name, String value) {
            attributes.put(name, value);
            return this;
        }

        /**
         * Builds the context.
         *
         * @return the context
         */
        public VariableContext build() {
            return new VariableContext(this);
        }
    }
}
