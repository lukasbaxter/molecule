package dev.molecule.api.config;

import dev.molecule.api.database.DatabaseNamespace;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A typed, validated configuration setting (SPEC §59).
 *
 * <p>Every setting a plugin exposes is declared as a key. The declaration carries the
 * type, the default, and any constraint — so the web panel can render an appropriate
 * control, an invalid value is rejected before it reaches the database, and reading the
 * value never involves a cast or a null check.
 *
 * <pre>{@code
 * static final ConfigKey<Integer> RTP_RADIUS =
 *         ConfigKey.integer(NAMESPACE, "rtp.radius", 10_000)
 *                  .constrained(radius -> radius >= 100 && radius <= 30_000_000,
 *                               "between 100 and 30000000");
 * }</pre>
 *
 * @param <T> the value type
 */
public final class ConfigKey<T> {

    /** Dotted paths, so the panel can group settings into sections. */
    private static final Pattern VALID_PATH = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");

    /** Bounded by the config table's path column. */
    private static final int MAX_PATH_LENGTH = 191;

    private final DatabaseNamespace namespace;
    private final String path;
    private final T defaultValue;
    private final Class<T> type;
    private final Function<String, T> parser;
    private final Function<T, String> serialiser;
    private final Predicate<T> constraint;
    private final String constraintDescription;

    private ConfigKey(
            DatabaseNamespace namespace,
            String path,
            T defaultValue,
            Class<T> type,
            Function<String, T> parser,
            Function<T, String> serialiser,
            Predicate<T> constraint,
            String constraintDescription) {
        this.namespace = namespace;
        this.path = path;
        this.defaultValue = defaultValue;
        this.type = type;
        this.parser = parser;
        this.serialiser = serialiser;
        this.constraint = constraint;
        this.constraintDescription = constraintDescription;
    }

    private static <T> ConfigKey<T> create(
            DatabaseNamespace namespace,
            String path,
            T defaultValue,
            Class<T> type,
            Function<String, T> parser,
            Function<T, String> serialiser) {

        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(defaultValue, "defaultValue");
        if (!VALID_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    "Invalid config path '"
                            + path
                            + "': expected dotted lowercase segments, such as 'rtp.radius'");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException(
                    "Config path '" + path + "' exceeds " + MAX_PATH_LENGTH + " characters");
        }
        return new ConfigKey<>(
                namespace, path, defaultValue, type, parser, serialiser, value -> true, "any value");
    }

    /**
     * Declares a string setting.
     *
     * @param namespace    the owning plugin's namespace
     * @param path         dotted path, such as {@code messages.prefix}
     * @param defaultValue value used until an administrator changes it
     * @return the key
     */
    public static ConfigKey<String> string(
            DatabaseNamespace namespace, String path, String defaultValue) {
        return create(namespace, path, defaultValue, String.class, raw -> raw, value -> value);
    }

    /**
     * Declares an integer setting.
     *
     * @param namespace    the owning plugin's namespace
     * @param path         dotted path
     * @param defaultValue value used until an administrator changes it
     * @return the key
     */
    public static ConfigKey<Integer> integer(
            DatabaseNamespace namespace, String path, int defaultValue) {
        return create(
                namespace, path, defaultValue, Integer.class, Integer::parseInt, String::valueOf);
    }

    /**
     * Declares a long setting.
     *
     * @param namespace    the owning plugin's namespace
     * @param path         dotted path
     * @param defaultValue value used until an administrator changes it
     * @return the key
     */
    public static ConfigKey<Long> longValue(
            DatabaseNamespace namespace, String path, long defaultValue) {
        return create(namespace, path, defaultValue, Long.class, Long::parseLong, String::valueOf);
    }

    /**
     * Declares a decimal setting.
     *
     * @param namespace    the owning plugin's namespace
     * @param path         dotted path
     * @param defaultValue value used until an administrator changes it
     * @return the key
     */
    public static ConfigKey<Double> decimal(
            DatabaseNamespace namespace, String path, double defaultValue) {
        return create(
                namespace, path, defaultValue, Double.class, Double::parseDouble, String::valueOf);
    }

    /**
     * Declares a boolean setting.
     *
     * @param namespace    the owning plugin's namespace
     * @param path         dotted path
     * @param defaultValue value used until an administrator changes it
     * @return the key
     */
    public static ConfigKey<Boolean> bool(
            DatabaseNamespace namespace, String path, boolean defaultValue) {
        return create(
                namespace,
                path,
                defaultValue,
                Boolean.class,
                ConfigKey::parseBoolean,
                String::valueOf);
    }

    /**
     * Declares a list-of-strings setting.
     *
     * <p>Stored newline-separated, so a value round-trips through the panel's text area
     * unchanged and stays readable in an export.
     *
     * @param namespace    the owning plugin's namespace
     * @param path         dotted path
     * @param defaultValue value used until an administrator changes it
     * @return the key
     */
    @SuppressWarnings("unchecked") // List<String>.class is not expressible in Java
    public static ConfigKey<List<String>> stringList(
            DatabaseNamespace namespace, String path, List<String> defaultValue) {
        return create(
                namespace,
                path,
                List.copyOf(defaultValue),
                (Class<List<String>>) (Class<?>) List.class,
                raw -> raw.isEmpty() ? List.of() : List.of(raw.split("\n", -1)),
                values -> String.join("\n", values));
    }

    /**
     * Returns a copy of this key that rejects values failing a constraint.
     *
     * <p>Checked before a change is written, so the database never holds a value the
     * plugin cannot use, and the panel can explain the rule rather than showing an error
     * after the fact.
     *
     * @param constraint  returns {@code true} for acceptable values
     * @param description how the rule reads to an administrator, such as
     *     {@code "between 100 and 30000000"}
     * @return the constrained key
     */
    public ConfigKey<T> constrained(Predicate<T> constraint, String description) {
        if (!constraint.test(defaultValue)) {
            throw new IllegalArgumentException(
                    "Default value for "
                            + this
                            + " does not satisfy its own constraint ("
                            + description
                            + ")");
        }
        return new ConfigKey<>(
                namespace, path, defaultValue, type, parser, serialiser, constraint, description);
    }

    /**
     * Parses a stored value.
     *
     * @param raw the serialised value from the database
     * @return the parsed value
     * @throws IllegalArgumentException if the stored value cannot be read as this type, or
     *     fails the constraint
     */
    public T parse(String raw) {
        T value;
        try {
            value = parser.apply(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Stored value for " + this + " is not a valid " + type.getSimpleName() + ": " + raw,
                    e);
        }
        return validate(value);
    }

    /**
     * Serialises a value for storage.
     *
     * @param value the value to store
     * @return the serialised form
     * @throws IllegalArgumentException if the value fails the constraint
     */
    public String serialise(T value) {
        return serialiser.apply(validate(value));
    }

    /**
     * Checks a value against this key's constraint.
     *
     * @param value the value to check
     * @return the same value
     * @throws IllegalArgumentException if it fails the constraint
     */
    public T validate(T value) {
        Objects.requireNonNull(value, "value");
        if (!constraint.test(value)) {
            throw new IllegalArgumentException(
                    "Invalid value for " + this + ": expected " + constraintDescription + ", got " + value);
        }
        return value;
    }

    /**
     * Returns the namespace owning this setting.
     *
     * @return the namespace
     */
    public DatabaseNamespace namespace() {
        return namespace;
    }

    /**
     * Returns the dotted path.
     *
     * @return the path
     */
    public String path() {
        return path;
    }

    /**
     * Returns the value used until an administrator changes it.
     *
     * @return the default
     */
    public T defaultValue() {
        return defaultValue;
    }

    /**
     * Returns the value type, for panel rendering.
     *
     * @return the type
     */
    public Class<T> type() {
        return type;
    }

    /**
     * Returns how this key's constraint reads to an administrator.
     *
     * @return the constraint description
     */
    public String constraintDescription() {
        return constraintDescription;
    }

    /**
     * Accepts the spellings an administrator is likely to type, rather than only the two
     * {@code Boolean.parseBoolean} accepts — which silently treats anything unrecognised
     * as {@code false}.
     */
    private static boolean parseBoolean(String raw) {
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException("Not a boolean: " + raw);
        };
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ConfigKey<?> key
                && namespace.prefix().equals(key.namespace.prefix())
                && path.equals(key.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace.prefix(), path);
    }

    @Override
    public String toString() {
        return namespace.prefix() + path;
    }
}
