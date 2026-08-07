package tools.dscode.common.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import io.cucumber.core.runner.StepBase;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.mappings.queries.Tokenized;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import static io.cucumber.core.runner.GlobalState.getClosestScenarioStepAncestor;
import static io.cucumber.core.runner.GlobalState.getRootScenarioStep;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.GlobalConstants.META_FLAG;

/** A mutable JSON-backed map with JSONata reads and writable path queries. */
public class NodeMap extends ValueFormatting {
    public static final String MAP_TYPE_KEY = META_FLAG + "_MapType";
    private final Set<MapConfigurations.DataSource> dataSources = new HashSet<>();
    private MapConfigurations.MapType mapType = MapConfigurations.MapType.DEFAULT;

    public NodeMap() {
        this(MapConfigurations.MapType.DEFAULT);
    }

    public NodeMap(MapConfigurations.MapType mapType) {
        super(MAPPER.createObjectNode());
        setMapType(mapType);
    }

    public NodeMap(ObjectNode root) {
        super(root);
        setMapType(MapConfigurations.MapType.DEFAULT);
    }
    public NodeMap(MapConfigurations.MapType mapType, ObjectNode root) {
        super(root);
        setMapType(mapType);
    }

    public NodeMap(MapConfigurations.MapType mapType, MapConfigurations.DataSource... sources) {
        this(mapType);
        setDataSource(sources);
    }

    public NodeMap(String path) {
        super((ObjectNode) FileAndDataParsing.buildJsonFromPath(path));
    }

    public NodeMap(Map<?, ?> map) {
        this(toObjectNode(map));
    }
    public NodeMap(LinkedListMultimap<?, ?> multimap) {
        this(toObjectNode(multimap));
    }

    public ObjectNode getRoot() {
        return materializeRoot();
    }

    protected ObjectNode materializeRoot() {
        return root;
    }

    public Object get(String query) {
        return readValue(resolveQuery(query));
    }

    public Object get(Tokenized query) {
        return readValue(query);
    }

    protected Tokenized resolveQuery(String query) {
        return new Tokenized(query);
    }

    protected Object readValue(Tokenized query) {
        ObjectNode currentRoot = materializeRoot();
        Object value = query.get(currentRoot);
        if (query.returnsWholeCollection || !(value instanceof ArrayNode)) {
            return value;
        }
        CollectionValue collectionValue = getCollectionValue(
                currentRoot,
                query.simplePropertyPath()
        );
        return collectionValue.matched() ? collectionValue.value() : value;
    }

    private CollectionValue getCollectionValue(
            ObjectNode currentRoot,
            List<String> properties
    ) {
        if (properties.isEmpty()) {
            return CollectionValue.NO_MATCH;
        }
        JsonNode current = currentRoot;
        boolean finalPropertyIsCollection = false;
        for (int index = 0; index < properties.size(); index++) {
            if (!(current instanceof ObjectNode object)) {
                return CollectionValue.NO_MATCH;
            }

            JsonNode child = object.get(properties.get(index));
            if (child == null) {
                return CollectionValue.NO_MATCH;
            }
            boolean collection = object.has(MAP_TYPE_KEY) && child instanceof ArrayNode;
            if (collection) {
                ArrayNode values = (ArrayNode) child;
                child = values.isEmpty() ? null : values.get(values.size() - 1);
            }
            if (index == properties.size() - 1) {
                finalPropertyIsCollection = collection;
            }
            current = child;
        }
        return finalPropertyIsCollection
                ? new CollectionValue(true, fromSafeJsonNode(current))
                : CollectionValue.NO_MATCH;
    }

    private record CollectionValue(boolean matched, Object value) {
        private static final CollectionValue NO_MATCH = new CollectionValue(false, null);
    }

    public List<JsonNode> getAsList(String query) {
        return readValues(resolveQuery(query));
    }

    public List<JsonNode> getAsList(Tokenized query) {
        return readValues(query);
    }

    protected List<JsonNode> readValues(Tokenized query) {
        return query.getList(materializeRoot());
    }

    public void put(String query, Object value) {
        writeValue(resolveQuery(query), value);
    }

    public void put(Tokenized query, Object value) {
        writeValue(query, value);
    }

    protected void writeValue(Tokenized query, Object value) {
        query.put(root, value);
    }

    public void putAsSingleton(String query, Object value) {
        writeValue(Tokenized.singletonWrite(query), value);
    }

    public void clearValues(String... keys) {
        if (keys == null || keys.length == 0) {
            clearAllValues();
            return;
        }
        Arrays.stream(keys)
                .filter(Objects::nonNull)
                .forEach(this::removeValue);
    }

    protected void clearAllValues() {
        root.removeAll();
    }

    protected void removeValue(String key) {
        root.remove(key);
    }

    public MapConfigurations.MapType getMapType() {
        return mapType;
    }
    public void setMapType(MapConfigurations.MapType mapType) {
        this.mapType = Objects.requireNonNullElse(mapType, MapConfigurations.MapType.DEFAULT);
        root.set(MAP_TYPE_KEY, toSafeJsonNode(this.mapType));
    }

    public Set<MapConfigurations.DataSource> getDataSources() {
        return Set.copyOf(dataSources);
    }
    public void setDataSource(MapConfigurations.DataSource... sources) {
        if (sources == null) {
            return;
        }
        Arrays.stream(sources)
                .filter(Objects::nonNull)
                .forEach(dataSources::add);
    }
    public void setDataSource(String... sources) {
        if (sources == null) {
            return;
        }
        Arrays.stream(sources)
                .filter(Objects::nonNull)
                .map(MapConfigurations.DataSource::fromString)
                .forEach(dataSources::add);
    }

    public void merge(ObjectNode other) {
        if (other != null) {
            root.setAll(other);
        }
    }
    public void merge(Map<?, ?> other) {
        if (other != null) {
            root.setAll(toObjectNode(other));
        }
    }

    public void merge(LinkedListMultimap<?, ?> other) {
        if (other != null) {
            root.setAll(toObjectNode(other));
        }
    }
    public void merge(List<?> keys, List<?> values) {
        if (keys == null || values == null) {
            return;
        }
        if (keys.size() != values.size()) {
            throw new IllegalArgumentException("Keys and values must have the same size");
        }
        LinkedListMultimap<Object, Object> multimap = LinkedListMultimap.create();
        IntStream.range(0, keys.size())
                .forEach(index -> multimap.put(keys.get(index), values.get(index)));
        merge(multimap);
    }
    @Override
    public String toString() {
        return "Type: " + mapType + " Source: " + dataSources + "\nroot:" + root;
    }

    private static ObjectNode toObjectNode(Map<?, ?> map) {
        return requireObjectNode(map == null ? Map.of() : map, "Map");
    }
    private static ObjectNode toObjectNode(LinkedListMultimap<?, ?> multimap) {
        if (multimap == null) {
            return MAPPER.createObjectNode();
        }
        Map<Object, Collection<Object>> values = new LinkedHashMap<>();
        multimap.asMap().forEach(
                (key, collection) -> values.put(key, new ArrayList<>(collection)));
        return requireObjectNode(values, "Multimap");
    }
    private static ObjectNode requireObjectNode(Object value, String description) {
        JsonNode node = toSafeJsonNode(value);
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalArgumentException(description + " did not serialize to an ObjectNode");
    }
    public static NodeMap getNodeMap(String input) {
        List<String> segments = input == null
                ? List.of()
                : Arrays.stream(input.split("\\."))
                .map(String::trim)
                .filter(segment -> !segment.isBlank())
                .map(segment -> segment.toUpperCase(Locale.ROOT))
                .toList();

        if (segments.isEmpty()) {
            return MappingProcessor.getRunMap();
        }
        String mapType = segments.getLast();
        return switch (mapType) {
            case "DEFAULT" -> MappingProcessor.getDefaultsMap();
            case "OVERRIDE" -> MappingProcessor.getOverridesMap();
            case "SINGLETON" -> MappingProcessor.getSingletonMap();
            case "SCENARIO ROOT", "ROOT SCENARIO" ->
                    getRootScenarioStep().getDefaultStepNodeMap();
            case "RUN" -> MappingProcessor.getRunMap();
            case "STEP" -> getStepNodeMap(input, segments);
            case "SCENARIO" -> getScenarioNodeMap(input, segments);
            default -> throw new IllegalArgumentException(
                    "Unsupported NodeMap reference '" + input + "'. "
                            + "The final segment must be DEFAULT, OVERRIDE, SINGLETON, "
                            + "SCENARIO ROOT, ROOT SCENARIO, RUN, STEP, or SCENARIO.");
        };
    }
    private static NodeMap getStepNodeMap(String input, List<String> segments) {
        validateParentPrefixes(input, segments);
        StepBase step = getRunningStep();
        for (int i = 0; i < segments.size() - 1; i++) {
            step = step.parentStep;
            if (step == null) {
                return null;
            }
        }
        return step.getDefaultStepNodeMap();
    }
    private static NodeMap getScenarioNodeMap(String input, List<String> segments) {
        validateParentPrefixes(input, segments);
        int parentCount = segments.size() - 1;
        StepExtension scenarioStep = getClosestScenarioStepAncestor();
        if (scenarioStep == null) {
            return null;
        }
        for (int i = 0; i < parentCount; i++) {
            scenarioStep = scenarioStep.getClosestScenarioStepAncestor();
            if (scenarioStep == null) {
                return null;
            }
        }
        return scenarioStep.getDefaultStepNodeMap();
    }
    private static void validateParentPrefixes(String input, List<String> segments) {
        List<String> invalidPrefixes = segments
                .subList(0, segments.size() - 1)
                .stream()
                .filter(segment -> !segment.equals("PARENT"))
                .toList();
        if (!invalidPrefixes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid NodeMap reference '" + input + "'. "
                            + "Only PARENT segments may precede STEP or SCENARIO. "
                            + "Invalid segment(s): " + invalidPrefixes);
        }
    }
}
