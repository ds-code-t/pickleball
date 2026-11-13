package dev.domengine.premap;

import dev.domengine.tree.DomNode;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 pass-through. {@link #PASS_THROUGH} and {@link #installed()} answer nothing,
 * so a caller evaluates the clause live. {@code current} stays empty until Phase 7.
 * G2's {@code RelationTerm} is not referenced here.
 */
public final class PreMapLookup {
    public static final PreMapLookup PASS_THROUGH = new PreMapLookup(true, null);
    private static final IdentityHashMap<DomNode, PreMap> TREES = new IdentityHashMap<>();
    private static final ConcurrentHashMap<String, PreMap> SESSIONS = new ConcurrentHashMap<>();

    private final boolean passThrough;
    private final PreMap bound;

    private PreMapLookup(boolean passThrough, PreMap bound) {
        this.passThrough = passThrough;
        this.bound = bound;
    }

    public static PreMapLookup installed() {
        return PASS_THROUGH;
    }

    public static void resetForTests() {
        synchronized (TREES) {
            TREES.clear();
        }
        SESSIONS.clear();
    }

    public static void store(String sessionId, PreMap map) {
        if (sessionId == null || sessionId.isBlank() || map == null) return;
        SESSIONS.put(sessionId, map);
    }

    /** Caches an offline tree by identity. Candidates stay empty until Phase 4 fills the index. */
    public static PreMapLookup forTree(DomNode root) {
        if (root == null) return PASS_THROUGH;
        PreMap map;
        synchronized (TREES) {
            map = TREES.computeIfAbsent(root, key -> PreMap.empty("offline", "fixture"));
        }
        return new PreMapLookup(false, map);
    }

    public Optional<PreMap> current(String sessionId, String docVersion) {
        return Optional.empty();
    }

    public Optional<List<String>> candidates(String clauseKind, String value) {
        if (passThrough || bound == null) return Optional.empty();
        return Optional.empty();
    }

    public boolean passThrough() {
        return passThrough;
    }
}
