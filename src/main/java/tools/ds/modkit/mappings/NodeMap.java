package tools.ds.modkit.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.collect.LinkedListMultimap;

import tools.ds.modkit.mappings.queries.Tokenized;

import java.util.*;

import static tools.ds.modkit.blackbox.BlackBoxBootstrap.metaFlag;

public class NodeMap {


    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        this.root.set(DataSourceKey, MAPPER.valueToTree(dataSource));
    }

    public enum DataSource {CONFIGURATION_FILE, PASSED_TABLE, EXAMPLE_TABLE, STEP_TABLE, DEFAULT}

    private DataSource dataSource = DataSource.DEFAULT;

    public ParsingMap.MapType getMapType() {
        return mapType;
    }

    public void setMapType(ParsingMap.MapType mapType) {
        this.mapType = mapType;
        this.root.set(MapTypeKey, MAPPER.valueToTree(mapType));
    }

    private ParsingMap.MapType mapType = ParsingMap.MapType.DEFAULT;

    public static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.registerModule(new GuavaModule());
    }

    public final static String MapTypeKey = metaFlag + "_MapType";
    public final static String DataSourceKey = metaFlag + "_DataSource";

    private final ObjectNode root;

    public NodeMap(ParsingMap.MapType mapType, DataSource dataSource) {
        this(mapType);
        this.dataSource = dataSource;
    }

    public NodeMap(ParsingMap.MapType mapType) {
        this.root = MAPPER.createObjectNode();
        setMapType(mapType);
    }

    public NodeMap() {
        this.root = MAPPER.createObjectNode();
        setMapType(mapType);
    }

    public NodeMap(Map<?, ?> map) {
        this.root = toObjectNode(map);
        setMapType(mapType);
    }

    public NodeMap(LinkedListMultimap<?, ?> multimap) {
        this.root = toObjectNode(multimap);
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
        (new Tokenized(key)).setWithPath(root, value);
    }


    // ---- Merge (ObjectNode, Map, LinkedListMultimap) ----
    public void merge(ObjectNode other) {
        if (other != null) root.setAll(other);
    }

    public void merge(Map<?, ?> other) {
        if (other != null) root.setAll(toObjectNode(other));
    }

    public void merge(LinkedListMultimap<?, ?> other) {
        if (other != null) root.setAll(toObjectNode(other));
    }

    // ---- Normalize constructor/merge inputs ----
    private static ObjectNode toObjectNode(Map<?, ?> map) {
        if (map == null) return MAPPER.createObjectNode();
        JsonNode n = MAPPER.valueToTree(map);
        if (n != null && n.isObject()) return (ObjectNode) n;
        throw new IllegalArgumentException("Map did not serialize to an ObjectNode");
    }

    private static ObjectNode toObjectNode(LinkedListMultimap<?, ?> mm) {
        if (mm == null) return MAPPER.createObjectNode();
        Map<Object, Collection<Object>> tmp = new LinkedHashMap<>();
        mm.asMap().forEach((k, coll) -> tmp.put(k, new ArrayList<>(coll)));
        JsonNode n = MAPPER.valueToTree(tmp);
        if (n != null && n.isObject()) return (ObjectNode) n;
        throw new IllegalArgumentException("Multimap did not serialize to an ObjectNode");
    }


}
