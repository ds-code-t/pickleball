package tools.dscode.common.mappings.custommappings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public abstract class CustomReader {
    protected final ObjectMapper mapper;

    protected CustomReader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public Object read(String input) throws IOException {
        return read((Object) input, Object.class);
    }

    public <T> T read(String input, Class<T> targetType) throws IOException {
        return read((Object) input, targetType);
    }

    public <T> T read(String input, TypeReference<T> targetType) throws IOException {
        return read((Object) input, targetType);
    }

    public Object read(Object input) throws IOException {
        return read(input, Object.class);
    }

    public <T> T read(Object input, Class<T> targetType) throws IOException {
        Object generic = readAsGenericGraph(input);
        Object modified = walk(generic, null);
        return convertGenericToType(modified, targetType);
    }

    public <T> T read(Object input, TypeReference<T> targetType) throws IOException {
        Object generic = readAsGenericGraph(input);
        Object modified = walk(generic, null);
        return convertGenericToType(modified, targetType);
    }

    public Object convertValue(Object input) {
        Object generic = toGenericGraph(input);
        return walk(generic, null);
    }

    public <T> T convertValue(Object input, Class<T> targetType) {
        Object generic = toGenericGraph(input);
        Object modified = walk(generic, null);
        return convertGenericToType(modified, targetType);
    }

    public <T> T convertValue(Object input, TypeReference<T> targetType) {
        Object generic = toGenericGraph(input);
        Object modified = walk(generic, null);
        return convertGenericToType(modified, targetType);
    }

    public JsonNode valueToTree(Object input) {
        Object generic = toGenericGraph(input);
        Object modified = walk(generic, null);
        return mapper.valueToTree(modified);
    }

    public <T> T treeToValue(JsonNode tree, Class<T> targetType) {
        return convertValue(tree, targetType);
    }

    public <T> T treeToValue(JsonNode tree, TypeReference<T> targetType) {
        return convertValue(tree, targetType);
    }

    public JsonNode readTree(String input) throws IOException {
        return readTreeInternal(input);
    }

    public JsonNode readTree(byte[] input) throws IOException {
        return readTreeInternal(input);
    }

    public JsonNode readTree(File input) throws IOException {
        return readTreeInternal(input);
    }

    public JsonNode readTree(URL input) throws IOException {
        return readTreeInternal(input);
    }

    public JsonNode readTree(InputStream input) throws IOException {
        return readTreeInternal(input);
    }

    public JsonNode readTree(Reader input) throws IOException {
        return readTreeInternal(input);
    }

    public JsonNode readTree(JsonParser input) throws IOException {
        return readTreeInternal(input);
    }

    public String writeValueAsString(Object value) throws JsonProcessingException {
        Object generic = toGenericGraph(value);
        Object modified = walk(generic, null);
        return mapper.writeValueAsString(modified);
    }

    public String writeValueAsPrettyString(Object value) throws JsonProcessingException {
        Object generic = toGenericGraph(value);
        Object modified = walk(generic, null);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(modified);
    }

    public <T> T modifyValue(T value) {
        if (value == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Class<T> runtimeType = (Class<T>) value.getClass();
        return convertValue(value, runtimeType);
    }

    protected abstract Object modify(Object value, Object parent);

    private JsonNode readTreeInternal(Object input) throws IOException {
        Object generic = readAsGenericGraph(input);
        Object modified = walk(generic, null);
        return mapper.valueToTree(modified);
    }

    private Object readAsGenericGraph(Object input) throws IOException {
        if (input == null) {
            return null;
        }

        if (input instanceof JsonNode node) {
            return mapper.convertValue(node, Object.class);
        }
        if (input instanceof String s) {
            return mapper.readValue(s, Object.class);
        }
        if (input instanceof byte[] bytes) {
            return mapper.readValue(bytes, Object.class);
        }
        if (input instanceof File file) {
            return mapper.readValue(file, Object.class);
        }
        if (input instanceof URL url) {
            return mapper.readValue(url, Object.class);
        }
        if (input instanceof InputStream in) {
            return mapper.readValue(in, Object.class);
        }
        if (input instanceof Reader reader) {
            return mapper.readValue(reader, Object.class);
        }
        if (input instanceof JsonParser parser) {
            return mapper.readValue(parser, Object.class);
        }

        return toGenericGraph(input);
    }

    private Object toGenericGraph(Object input) {
        if (input == null) {
            return null;
        }

        if (input instanceof JsonNode node) {
            return mapper.convertValue(node, Object.class);
        }

        return mapper.convertValue(mapper.valueToTree(input), Object.class);
    }

    private <T> T convertGenericToType(Object value, Class<T> targetType) {
        if (value == null) {
            return null;
        }

        if (targetType == Object.class) {
            return targetType.cast(value);
        }

        return mapper.convertValue(value, targetType);
    }

    private <T> T convertGenericToType(Object value, TypeReference<T> targetType) {
        if (value == null) {
            return null;
        }

        return mapper.convertValue(value, targetType);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object walk(Object value, Object parent) {
        if (value instanceof Map<?, ?> rawMap) {
            Map map = (Map) rawMap;
            for (Object rawEntry : new ArrayList<>(map.entrySet())) {
                Map.Entry entry = (Map.Entry) rawEntry;
                map.put(entry.getKey(), walk(entry.getValue(), map));
            }
        } else if (value instanceof List<?> rawList) {
            List list = (List) rawList;
            for (int i = 0; i < list.size(); i++) {
                list.set(i, walk(list.get(i), list));
            }
        } else if (value instanceof Set<?> rawSet) {
            Set set = (Set) rawSet;
            List<Object> updated = new ArrayList<>(set.size());
            for (Object item : set) {
                updated.add(walk(item, set));
            }
            set.clear();
            set.addAll(updated);
        } else if (value instanceof Collection<?> rawCollection) {
            Collection collection = (Collection) rawCollection;
            List<Object> updated = new ArrayList<>(collection.size());
            for (Object item : collection) {
                updated.add(walk(item, collection));
            }
            collection.clear();
            collection.addAll(updated);
        } else if (value instanceof Object[] array) {
            for (int i = 0; i < array.length; i++) {
                array[i] = walk(array[i], array);
            }
        }

        return modify(value, parent);
    }
}