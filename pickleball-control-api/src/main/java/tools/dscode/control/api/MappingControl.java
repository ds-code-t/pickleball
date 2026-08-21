package tools.dscode.control.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.runner.GlobalState;
import tools.dscode.common.mappings.GlobalMappings;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.control.protocol.ControlProtocol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

/** Retry-friendly mapping inspection, mutation, isolation, snapshot, and emulation utilities. */
public final class MappingControl {
    private MappingControl() {
    }

    public static MappingContext single(NodeMap map) {
        return custom("single " + requireMap(map).getMapType(), map);
    }

    public static MappingContext overrideOnly() {
        return overrideOnly(Map.of());
    }

    public static MappingContext overrideOnly(Map<String, ?> values) {
        NodeMap override = new NodeMap(MapConfigurations.MapType.OVERRIDE_MAP);
        if (values != null) {
            values.forEach(override::put);
        }
        return custom("override only", override);
    }

    /** Uses a detached snapshot of GLOBALS so exploratory writes cannot mutate the live global map. */
    public static MappingContext overrideWithGlobals() {
        return overrideWithGlobals(Map.of());
    }

    /** Uses a detached snapshot of GLOBALS so exploratory writes cannot mutate the live global map. */
    public static MappingContext overrideWithGlobals(Map<String, ?> overrides) {
        NodeMap override = new NodeMap(MapConfigurations.MapType.OVERRIDE_MAP);
        if (overrides != null) {
            overrides.forEach(override::put);
        }
        NodeMap globals = copy(GlobalMappings.GLOBALS);
        return custom("override + globals snapshot", override, globals);
    }

    public static MappingContext custom(NodeMap... maps) {
        return custom("custom", maps);
    }

    public static MappingContext custom(String description, NodeMap... maps) {
        Objects.requireNonNull(maps, "maps");
        return new MappingContext(description, List.of(maps));
    }

    public static NodeMap nodeMap(MapConfigurations.MapType type) {
        return new NodeMap(Objects.requireNonNull(type, "type"));
    }

    public static NodeMap nodeMap(MapConfigurations.MapType type, Map<String, ?> values) {
        NodeMap nodeMap = nodeMap(type);
        if (values != null) {
            values.forEach(nodeMap::put);
        }
        return nodeMap;
    }

    /** Detached materialized copy suitable for isolated experiments. */
    public static NodeMap copy(NodeMap source) {
        Objects.requireNonNull(source, "source");
        NodeMap copy = new NodeMap(source.getMapType(), source.getRoot().deepCopy());
        source.getDataSources().forEach(dataSource -> copy.setDataSource(dataSource.name()));
        return copy;
    }

    public static ControlCallResult<ParsingMap> current() {
        return attempt(ParsingMap::getRunningParsingMap);
    }

    /**
     * Resolves normal Pickleball NodeMap references plus the two neutral Workbench
     * references defined in {@link ControlProtocol}. The Workbench references are
     * intentionally resolved here, inside the consumer worker, so controller code
     * never needs ParsingMap/NodeMap classes or a shared execution classpath.
     */
    public static ControlCallResult<NodeMap> currentNodeMap(String reference) {
        if (reference == null || reference.isBlank()) {
            return ControlCallResult.unavailable("NodeMap reference must not be blank.");
        }
        String normalized = reference.trim();
        if (ControlProtocol.CURRENT_NODE_MAP_CATALOG_REFERENCE.equals(normalized)) {
            return attempt(MappingControl::currentNodeMapCatalog);
        }
        if (normalized.startsWith(ControlProtocol.CURRENT_NODE_MAP_REFERENCE_PREFIX)) {
            return attempt(() -> currentNodeMapByIndex(normalized));
        }
        return attempt(() -> NodeMap.getNodeMap(normalized));
    }

    public static ControlCallResult<NodeMap> currentNodeMapCopy(String reference) {
        ControlCallResult<NodeMap> current = currentNodeMap(reference);
        if (!current.successful()) {
            return current;
        }
        return attempt(() -> copy(current.value()));
    }

    public static ControlCallResult<Object> get(String mapReference, String key) {
        ControlCallResult<NodeMap> map = currentNodeMap(mapReference);
        if (!map.successful()) {
            return new ControlCallResult<>(map.status(), null, map.error());
        }
        return attempt(() -> map.value().get(key));
    }

    public static ControlCallResult<NodeMap> put(String mapReference, String key, Object value) {
        ControlCallResult<NodeMap> map = currentNodeMap(mapReference);
        if (!map.successful()) {
            return map;
        }
        return attempt(() -> {
            map.value().put(key, value);
            return map.value();
        });
    }

    public static ControlCallResult<NodeMap> clear(String mapReference, String... rootKeys) {
        ControlCallResult<NodeMap> map = currentNodeMap(mapReference);
        if (!map.successful()) {
            return map;
        }
        return attempt(() -> {
            map.value().clearValues(rootKeys);
            return map.value();
        });
    }

    public static ControlCallResult<OverrideScope> overrideScope(Map<String, ?> values) {
        return attempt(() -> {
            NodeMap override = MappingProcessor.getOverridesMap();
            if (override == null) {
                throw new IllegalStateException("The current thread has no OVERRIDE NodeMap.");
            }
            OverrideScope scope = new OverrideScope(override);
            try {
                if (values != null) {
                    values.forEach(override::put);
                }
                return scope;
            } catch (Throwable error) {
                scope.close();
                if (error instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (error instanceof Error fatalError) {
                    throw fatalError;
                }
                throw new RuntimeException(error);
            }
        });
    }

    /** Temporarily replaces the currently running ParsingMap's map references, restoring them exactly on close. */
    public static ControlCallResult<MappingScope> useCurrent(MappingContext context) {
        if (GlobalState.getCurrentScenarioState() == null) {
            return ControlCallResult.unavailable("No scenario is currently active.");
        }
        if (context == null) {
            return ControlCallResult.unavailable("mapping context must not be null");
        }
        return attempt(() -> new MappingScope(ParsingMap.getRunningParsingMap(), context));
    }

    public static <T> ControlCallResult<T> withCurrent(
            MappingContext context,
            Supplier<T> action
    ) {
        Objects.requireNonNull(action, "action");
        ControlCallResult<MappingScope> scopeResult = useCurrent(context);
        if (!scopeResult.successful()) {
            return new ControlCallResult<>(scopeResult.status(), null, scopeResult.error());
        }
        try (MappingScope ignored = scopeResult.value()) {
            return attempt(action);
        }
    }

    public static ControlCallResult<String> resolveText(MappingContext context, String input) {
        if (context == null) {
            return ControlCallResult.unavailable("mappingContext must not be null");
        }
        return attempt(() -> context.parsingMap().resolveWholeText(input));
    }

    public static ControlCallResult<Object> resolveValue(MappingContext context, String input) {
        if (context == null) {
            return ControlCallResult.unavailable("mappingContext must not be null");
        }
        return attempt(() -> context.parsingMap().resolveWholeValue(input));
    }

    public static ControlCallResult<ResolutionExplanation> explain(
            ParsingMap parsingMap,
            String key
    ) {
        if (parsingMap == null) {
            return ControlCallResult.unavailable("parsingMap must not be null");
        }
        if (key == null || key.isBlank()) {
            return ControlCallResult.unavailable("key must not be blank");
        }
        return attempt(() -> {
            List<ResolutionCandidate> searched = new ArrayList<>();
            Object resolved = null;
            String winner = "";
            int order = 0;
            for (NodeMap map : parsingMap.getMapsForResolution()) {
                Object value = map == null ? null : map.get(key);
                boolean matched = value != null;
                searched.add(new ResolutionCandidate(
                        order++,
                        map == null ? "" : map.getMapType().name(),
                        matched,
                        value
                ));
                if (matched) {
                    resolved = value;
                    winner = map.getMapType().name();
                    break;
                }
            }
            return new ResolutionExplanation(key, searched, winner, resolved);
        });
    }

    public static ControlCallResult<MappingSnapshot> snapshotCurrent() {
        ControlCallResult<ParsingMap> current = current();
        if (!current.successful()) {
            return new ControlCallResult<>(current.status(), null, current.error());
        }
        return snapshot(current.value());
    }

    public static ControlCallResult<MappingSnapshot> snapshot(ParsingMap parsingMap) {
        if (parsingMap == null) {
            return ControlCallResult.unavailable("parsingMap must not be null");
        }
        return attempt(() -> {
            List<NodeMapSnapshot> maps = new ArrayList<>();
            Set<NodeMap> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (NodeMap nodeMap : parsingMap.getMapsForResolution()) {
                if (nodeMap == null || !seen.add(nodeMap)) {
                    continue;
                }
                ObjectNode values = nodeMap.getRoot().deepCopy();
                maps.add(new NodeMapSnapshot(
                        nodeMap.getMapType().name(),
                        nodeMap.getDataSources().stream().map(Enum::name).sorted().toList(),
                        values
                ));
            }
            return new MappingSnapshot(MappingSnapshot.CURRENT_VERSION, maps);
        });
    }

    public static ControlCallResult<Path> saveSnapshot(MappingSnapshot snapshot, Path file) {
        if (snapshot == null || file == null) {
            return ControlCallResult.unavailable("snapshot and file must not be null");
        }
        return attempt(() -> {
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    java.nio.file.Files.createDirectories(parent);
                }
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot);
                return file;
            } catch (IOException error) {
                throw new IllegalStateException("Could not save mapping snapshot to " + file, error);
            }
        });
    }

    public static ControlCallResult<MappingSnapshot> loadSnapshot(Path file) {
        if (file == null) {
            return ControlCallResult.unavailable("file must not be null");
        }
        return attempt(() -> {
            try {
                MappingSnapshot snapshot = MAPPER.readValue(file.toFile(), MappingSnapshot.class);
                if (snapshot.version() != MappingSnapshot.CURRENT_VERSION) {
                    throw new IllegalArgumentException(
                            "Unsupported mapping snapshot version: " + snapshot.version()
                    );
                }
                return snapshot;
            } catch (IOException error) {
                throw new IllegalStateException("Could not load mapping snapshot from " + file, error);
            }
        });
    }

    public static ControlCallResult<MappingContext> fromSnapshot(MappingSnapshot snapshot) {
        if (snapshot == null) {
            return ControlCallResult.unavailable("snapshot must not be null");
        }
        return attempt(() -> {
            if (snapshot.version() != MappingSnapshot.CURRENT_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported mapping snapshot version: " + snapshot.version()
                );
            }
            List<NodeMap> maps = new ArrayList<>();
            for (NodeMapSnapshot node : snapshot.maps()) {
                MapConfigurations.MapType type = MapConfigurations.MapType.valueOf(node.mapType());
                ObjectNode values = node.values();
                NodeMap map = new NodeMap(
                        type,
                        values == null ? MAPPER.createObjectNode() : values
                );
                map.setDataSource(node.dataSources().toArray(String[]::new));
                maps.add(map);
            }
            return new MappingContext("snapshot", maps);
        });
    }

    static ParsingMap isolatedParsingMap(List<NodeMap> maps) {
        ParsingMap parsingMap = new ParsingMap();
        installExact(parsingMap, maps);
        return parsingMap;
    }

    static void installExact(ParsingMap target, List<NodeMap> maps) {
        List<MapConfigurations.MapType> order = distinctOrder(maps);
        installExact(target, maps, order);
    }

    static void installExact(
            ParsingMap target,
            List<NodeMap> maps,
            List<MapConfigurations.MapType> order
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(maps, "maps");
        target.removeMaps(MapConfigurations.MapType.values());
        target.keyOrder().clear();
        target.addMaps(maps);
        target.keyOrder().addAll(order);
    }

    private static List<MapConfigurations.MapType> distinctOrder(List<NodeMap> maps) {
        LinkedHashSet<MapConfigurations.MapType> order = new LinkedHashSet<>();
        maps.stream().filter(Objects::nonNull).map(NodeMap::getMapType).forEach(order::add);
        return List.copyOf(order);
    }

    private static NodeMap currentNodeMapCatalog() {
        List<NodeMap> maps = distinctCurrentNodeMaps();
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode entries = root.putArray("maps");
        for (int index = 0; index < maps.size(); index++) {
            NodeMap map = maps.get(index);
            ObjectNode entry = entries.addObject();
            entry.put("reference", ControlProtocol.CURRENT_NODE_MAP_REFERENCE_PREFIX + index);
            entry.put("label", map.getMapType().name());
            entry.put("mapType", map.getMapType().name());
            entry.put("mapClass", map.getClass().getName());
            entry.put("restorable", map.getClass() == NodeMap.class);
            ArrayNode sources = entry.putArray("dataSources");
            map.getDataSources().stream()
                    .map(Enum::name)
                    .sorted()
                    .forEach(sources::add);
        }

        /*
         * Anonymous subclass intentionally makes the catalog inspection-only.
         * The bridge's existing snapshot logic marks only exact NodeMap instances
         * as restorable.
         */
        return new NodeMap(MapConfigurations.MapType.DEFAULT, root) { };
    }

    private static NodeMap currentNodeMapByIndex(String reference) {
        String indexText = reference.substring(ControlProtocol.CURRENT_NODE_MAP_REFERENCE_PREFIX.length());
        int index;
        try {
            index = Integer.parseInt(indexText);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid current NodeMap reference: " + reference, failure);
        }
        List<NodeMap> maps = distinctCurrentNodeMaps();
        if (index < 0 || index >= maps.size()) {
            throw new IllegalArgumentException(
                    "Current NodeMap reference is no longer available: " + reference
            );
        }
        return maps.get(index);
    }

    private static List<NodeMap> distinctCurrentNodeMaps() {
        ParsingMap parsingMap = ParsingMap.getRunningParsingMap();
        Set<NodeMap> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<NodeMap> maps = new ArrayList<>();
        for (NodeMap map : parsingMap.getMapsForResolution()) {
            if (map != null && seen.add(map)) {
                maps.add(map);
            }
        }
        return List.copyOf(maps);
    }

    private static NodeMap requireMap(NodeMap map) {
        return Objects.requireNonNull(map, "map");
    }

    private static <T> ControlCallResult<T> attempt(Supplier<T> action) {
        try {
            return ControlCallResult.success(action.get());
        } catch (Throwable error) {
            return ControlCallResult.failed(error);
        }
    }
}
