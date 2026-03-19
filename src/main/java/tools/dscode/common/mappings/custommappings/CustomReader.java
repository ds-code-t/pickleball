package tools.dscode.common.mappings.custommappings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        T root = materializeForRead(input, targetType);
        @SuppressWarnings("unchecked")
        T modified = (T) walk(root, null);
        return modified;
    }

    public <T> T read(Object input, TypeReference<T> targetType) throws IOException {
        T root = materializeForRead(input, targetType);
        @SuppressWarnings("unchecked")
        T modified = (T) walk(root, null);
        return modified;
    }

    public Object convertValue(Object input) {
        return convertValue(input, Object.class);
    }

    public <T> T convertValue(Object input, Class<T> targetType) {
        Object generic = toGenericGraph(input);
        Object modified = walk(generic, null);
        return mapper.convertValue(modified, targetType);
    }

    public <T> T convertValue(Object input, TypeReference<T> targetType) {
        Object generic = toGenericGraph(input);
        Object modified = walk(generic, null);
        return mapper.convertValue(modified, targetType);
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
        @SuppressWarnings("unchecked")
        T modified = (T) walk(value, null);
        return modified;
    }

    protected abstract Object modify(Object value, Object parent);

    private JsonNode readTreeInternal(Object input) throws IOException {
        Object generic = readAsGenericGraph(input);
        Object modified = walk(generic, null);
        return mapper.valueToTree(modified);
    }

    private <T> T materializeForRead(Object input, Class<T> targetType) throws IOException {
        if (input == null) {
            return null;
        }

        if (targetType.isInstance(input) && !isJsonSource(input)) {
            return targetType.cast(input);
        }

        if (input instanceof JsonNode node) {
            return mapper.treeToValue(node, targetType);
        }
        if (input instanceof String s) {
            return mapper.readValue(s, targetType);
        }
        if (input instanceof byte[] bytes) {
            return mapper.readValue(bytes, targetType);
        }
        if (input instanceof File file) {
            return mapper.readValue(file, targetType);
        }
        if (input instanceof URL url) {
            return mapper.readValue(url, targetType);
        }
        if (input instanceof InputStream in) {
            return mapper.readValue(in, targetType);
        }
        if (input instanceof Reader reader) {
            return mapper.readValue(reader, targetType);
        }
        if (input instanceof JsonParser parser) {
            return mapper.readValue(parser, targetType);
        }

        return mapper.convertValue(input, targetType);
    }

    private <T> T materializeForRead(Object input, TypeReference<T> targetType) throws IOException {
        if (input == null) {
            return null;
        }

        if (matchesRawTargetType(input, targetType) && !isJsonSource(input)) {
            @SuppressWarnings("unchecked")
            T same = (T) input;
            return same;
        }

        if (input instanceof JsonNode node) {
            return mapper.convertValue(node, targetType);
        }
        if (input instanceof String s) {
            return mapper.readValue(s, targetType);
        }
        if (input instanceof byte[] bytes) {
            return mapper.readValue(bytes, targetType);
        }
        if (input instanceof File file) {
            return mapper.readValue(file, targetType);
        }
        if (input instanceof URL url) {
            return mapper.readValue(url, targetType);
        }
        if (input instanceof InputStream in) {
            return mapper.readValue(in, targetType);
        }
        if (input instanceof Reader reader) {
            return mapper.readValue(reader, targetType);
        }
        if (input instanceof JsonParser parser) {
            return mapper.readValue(parser, targetType);
        }

        return mapper.convertValue(input, targetType);
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
        if (input instanceof Map<?, ?> || input instanceof List<?> || input instanceof Object[]) {
            return input;
        }

        return mapper.convertValue(input, Object.class);
    }

    private boolean matchesRawTargetType(Object input, TypeReference<?> targetType) {
        JavaType javaType = mapper.getTypeFactory().constructType(targetType.getType());
        Class<?> rawClass = javaType.getRawClass();
        return rawClass != null && rawClass.isInstance(input);
    }

    private boolean isJsonSource(Object input) {
        return input instanceof String
                || input instanceof byte[]
                || input instanceof File
                || input instanceof URL
                || input instanceof InputStream
                || input instanceof Reader
                || input instanceof JsonParser;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Object walk(Object value, Object parent) {
        if (value instanceof Map<?, ?> rawMap) {
            Map map = (Map) rawMap;
            for (Object rawEntry : new ArrayList<>(map.entrySet())) {
                Map.Entry entry = (Map.Entry) rawEntry;
                entry.setValue(walk(entry.getValue(), map));
            }
        } else if (value instanceof List<?> rawList) {
            List list = (List) rawList;
            for (int i = 0; i < list.size(); i++) {
                list.set(i, walk(list.get(i), list));
            }
        } else if (value instanceof Object[] array) {
            for (int i = 0; i < array.length; i++) {
                array[i] = walk(array[i], array);
            }
        }

        return modify(value, parent);
    }
}