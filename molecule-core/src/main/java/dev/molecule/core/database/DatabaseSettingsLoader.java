package dev.molecule.core.database;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Reads {@link DatabaseSettings} from bootstrap YAML (SPEC §4).
 *
 * <p>This is the one part of Molecule that genuinely belongs in a file: it is what Molecule
 * needs before it can reach the database that owns everything else. Live configuration is
 * read from MariaDB, not from here.
 */
public final class DatabaseSettingsLoader {

    private static final int DEFAULT_PORT = 3306;
    private static final int DEFAULT_POOL_SIZE = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private DatabaseSettingsLoader() {}

    /**
     * Reads settings from a configuration section.
     *
     * @param section the {@code database} section of the bootstrap config
     * @return the parsed settings
     * @throws IllegalArgumentException if the section is missing or a value is unusable
     */
    public static DatabaseSettings load(ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException(
                    "No 'database' section in config.yml. Molecule cannot start without one —"
                            + " see the generated config.yml for the expected shape.");
        }

        Map<String, String> properties = new LinkedHashMap<>();
        ConfigurationSection propertySection = section.getConfigurationSection("properties");
        if (propertySection != null) {
            for (String key : propertySection.getKeys(false)) {
                properties.put(key, String.valueOf(propertySection.get(key)));
            }
        }

        return new DatabaseSettings(
                section.getString("host", "127.0.0.1"),
                section.getInt("port", DEFAULT_PORT),
                section.getString("name", "molecule"),
                section.getString("username", "molecule"),
                section.getString("password", ""),
                section.getBoolean("ssl", false),
                section.getInt("pool-size", DEFAULT_POOL_SIZE),
                Duration.ofSeconds(section.getInt("connection-timeout-seconds", DEFAULT_TIMEOUT_SECONDS)),
                properties);
    }
}
