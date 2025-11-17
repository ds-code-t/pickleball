package tools.dscode.common.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.collect.LinkedListMultimap;
import tools.dscode.common.mappings.queries.Tokenized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static tools.dscode.common.GlobalConstants.META_FLAG;


public class NodeMap  extends ValueFormatting{

    public ObjectNode getRoot() {
        return root;
    }


    @Override
    public String toString() {
        return "Type: " + mapType + " Source: " + dataSources + "\nroot:" + root.toString();
    }

    public List<MapConfigurations.DataSource> getDataSources() {
        return dataSources;
    }

    public void setDataSource(MapConfigurations.DataSource... dataSources) {
        this.dataSources.addAll(List.of(dataSources));
    }

    private List<MapConfigurations.DataSource> dataSources = new ArrayList<>();

    public MapConfigurations.MapType getMapType() {
        return mapType;
    }

    public final static String MapTypeKey = META_FLAG + "_MapType";
    // public final static String DataSourceKey = META_FLAG + "_DataSource";

    public void setMapType(MapConfigurations.MapType mapType) {
        this.mapType = mapType;
        this.root.set(MapTypeKey, toSafeJsonNode(mapType));
    }

    private MapConfigurations.MapType mapType = MapConfigurations.MapType.DEFAULT;

    public static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.registerModule(new GuavaModule());
    }

    public NodeMap(MapConfigurations.MapType mapType, MapConfigurations.DataSource... dataSources) {
        this(mapType);
        this.dataSources = Arrays.stream(dataSources).toList();
    }

    public NodeMap(MapConfigurations.MapType mapType) {
        super(MAPPER.createObjectNode());
        setMapType(mapType);
    }

    public NodeMap() {
        super(MAPPER.createObjectNode());
        setMapType(mapType);
    }

    public NodeMap(Map<?, ?> map) {
        super(toObjectNode(map));
        setMapType(mapType);
    }

    public NodeMap(LinkedListMultimap<?, ?> multimap) {
        super(toObjectNode(multimap));
        setMapType(mapType);
    }

    public ObjectNode objectNode() {
        return root;
    }

    public List<JsonNode> getAsList(Tokenized tokenized) {
        return tokenized.getList(root);
    }

    public List<JsonNode> getAsList(String key) {
        return (new Tokenized(key).getList(root));
    }

    public Object get(Tokenized tokenized) {
        return tokenized.get(root);
    }

    public Object get(String key) {
        return (new Tokenized(key)).get(root);
    }

    public void put(String key, Object value) {
        put(new Tokenized(key), value);
    }

    public void put(Tokenized key, Object value) {
        key.setWithPath(root, value);
    }

    // ---- Merge (ObjectNode, Map, LinkedListMultimap) ----
    public void merge(ObjectNode other) {
        if (other != null)
            root.setAll(other);
    }

    public void merge(List<?> keys, List<?> values) {
        if (keys == null || values == null) {
            return; // or throw, depending on your desired behavior
        }

        if (keys.size() != values.size()) {
            throw new IllegalArgumentException("Keys and values must have the same size");
        }

        Map<Object, Object> map = IntStream.range(0, keys.size())
                .boxed()
                .collect(Collectors.toMap(keys::get, values::get));

        merge(map); // delegate to your original method
    }


    public void merge(Map<?, ?> other) {
        if (other != null)
            root.setAll(toObjectNode(other));
    }

    public void merge(LinkedListMultimap<?, ?> other) {
        if (other != null)
            root.setAll(toObjectNode(other));
    }

    // ---- Normalize constructor/merge inputs ----
    private static ObjectNode toObjectNode(Map<?, ?> map) {
        if (map == null)
            return MAPPER.createObjectNode();
        JsonNode n = toSafeJsonNode(map);
        if (n != null && n.isObject())
            return (ObjectNode) n;
        throw new IllegalArgumentException("Map did not serialize to an ObjectNode");
    }

    private static ObjectNode toObjectNode(LinkedListMultimap<?, ?> mm) {
        if (mm == null)
            return MAPPER.createObjectNode();
        Map<Object, Collection<Object>> tmp = new LinkedHashMap<>();
        mm.asMap().forEach((k, coll) -> tmp.put(k, new ArrayList<>(coll)));
        JsonNode n = toSafeJsonNode(tmp);
        if (n != null && n.isObject())
            return (ObjectNode) n;
        throw new IllegalArgumentException("Multimap did not serialize to an ObjectNode");
    }




}
