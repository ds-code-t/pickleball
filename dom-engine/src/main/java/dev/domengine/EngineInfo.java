package dev.domengine;

import java.util.List;

/**
 * Identity of this general-purpose engine. It does not know about Pickleball.
 */
public final class EngineInfo {
    public static final String NAME = "domengine";
    public static final String VERSION = "3.0.0";
    /** Composite snapshot JSON schema version advertised by `capabilities`. */
    public static final int SNAPSHOT_SCHEMA = 1;
    /** Pre-map command and artifact are part of this engine build. */
    public static final boolean PREMAP = true;
    /** CLI name accepted beside {@code domengine}. */
    public static final String COMPAT_COMMAND = "composite";

    public static final List<String> LOG_ENTITIES = List.of(
            "cli", "serve", "capture", "cdp", "walk", "query", "resolve",
            "session", "action", "wait", "observe", "desc", "desktop", "image", "visual", "expr", "look", "log",
            "snapshot", "premap", "affinity", "desktop-shot"
    );

    private EngineInfo() {
    }
}
