package tools.dscode.common.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import tools.dscode.common.mappings.queries.Tokenized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

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
        return root;
    }

    public Object get(String query) {
        return new Tokenized(query).get(root);
    }

    public Object get(Tokenized query) {
        return query.get(root);
    }

    public List<JsonNode> getAsList(String query) {
        return new Tokenized(query).getList(root);
    }

    public List<JsonNode> getAsList(Tokenized query) {
        return query.getList(root);
    }

    public void put(String query, Object value) {
        new Tokenized(query).put(root, value);
    }

    public void put(Tokenized query, Object value) {
        query.put(root, value);
    }

    public void putAsSingleton(String query, Object value) {
        Tokenized.singletonWrite(query).put(root, value);
    }

    public void clearValues(String... keys) {
        if (keys == null || keys.length == 0) {
            root.removeAll();
            return;
        }
        Arrays.stream(keys)
                .filter(Objects::nonNull)
                .forEach(root::remove);
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
}
