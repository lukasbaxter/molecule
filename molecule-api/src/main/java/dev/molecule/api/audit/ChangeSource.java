package dev.molecule.api.audit;

/**
 * Where a change came from (SPEC §6).
 *
 * <p>Recorded on every audit entry, because "who" alone does not explain a change — the
 * same administrator acting through the panel, a command, and a bulk import are three
 * different situations when reading history back.
 */
public enum ChangeSource {

    /** Edited in the web panel. */
    WEB,

    /** Made by an in-game or console command. */
    COMMAND,

    /** Made by a third-party plugin through the Molecule API. */
    API,

    /** Applied by importing a YAML file (SPEC §4). */
    IMPORT,

    /** Written by Molecule itself, such as seeding a newly declared setting's default. */
    SYSTEM
}
