// AbstractMultiMap.java
package io.pickleball.mapandStateutilities;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public abstract class AbstractMultiMap<K, V> extends HashMap<K, V> implements MultiMapOperations<K, V> {
    protected final List<K> keys;
    protected final List<V> values;

    protected AbstractMultiMap() {
        this.keys = new ArrayList<>();
        this.values = new ArrayList<>();
    }

    protected AbstractMultiMap(List<K> keys, List<V> values) {
        Objects.requireNonNull(keys, "Keys list cannot be null");
        Objects.requireNonNull(values, "Values list cannot be null");
        if (keys.size() != values.size()) {
            throw new IllegalArgumentException("Keys and values lists must have the same size");
        }
        this.keys = new ArrayList<>(keys);
        this.values = new ArrayList<>(values);
    }

    public void addAll(K key, List<V> newValues) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(newValues, "Values list cannot be null");

        // Append the new values to the existing list or create a new list if it doesn't exist
        for (V value : newValues) {
            keys.add(key);
            values.add(value);
        }
    }

    @Override
    public V put(K key, V value) {
        List<V> vs = getValues(key);
        V previous = vs.isEmpty() ? null : vs.get(vs.size() - 1);
        keys.add(key);
        values.add(value);
        return previous;
    }

    @Override
    public List<V> getValues(K key) {
        Objects.requireNonNull(key, "Key cannot be null");
        List<V> result = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            if (Objects.equals(keys.get(i), key)) {
                result.add(values.get(i));
            }
        }
        return result;
    }

    @Override
    public List<K> getKeys(V value) {
        List<K> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (Objects.equals(values.get(i), value)) {
                result.add(keys.get(i));
            }
        }
        return result;
    }

    @Override
    public int size() {
        return keys.size();
    }

    @Override
    public boolean isEmpty() {
        return keys.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return keys.contains(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return values.contains(value);
    }

    @Override
    public void clear() {
        keys.clear();
        values.clear();
    }

    @Override
    public Set<K> keySet() {
        return new LinkedHashSet<>(keys);
    }

    @Override
    public Collection<V> values() {
        return new ArrayList<>(values);
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        Set<Map.Entry<K, V>> entries = new LinkedHashSet<>();
        for (int i = 0; i < keys.size(); i++) {
            final int index = i;
            entries.add(new AbstractMap.SimpleImmutableEntry<>(keys.get(index), values.get(index)));
        }
        return entries;
    }

    @Override
    public String toString() {
        return "{" +
                IntStream.range(0, keys.size())
                        .mapToObj(i -> keys.get(i) + "=" + (values.get(i) != null ? values.get(i) : "null"))
                        .collect(Collectors.joining(", "))
                + "}";
    }


    /**
     * Merges multiple AbstractMultiMap&lt;K, V&gt; instances of the same key/value types into a new instance.
     * The entries are appended in the order of the varargs.
     */
    @SafeVarargs
    public static <K, V> AbstractMultiMap<K, V> merge(AbstractMultiMap<K, V>... multiMaps) {
        Objects.requireNonNull(multiMaps, "multiMaps array cannot be null");
        // Anonymous subclass to instantiate an AbstractMultiMap
        AbstractMultiMap<K, V> merged = new AbstractMultiMap<K, V>() { };
        for (AbstractMultiMap<K, V> map : multiMaps) {
            if (map == null) {
                continue;
            }
            for (int i = 0; i < map.keys.size(); i++) {
                merged.keys.add(map.keys.get(i));
                merged.values.add(map.values.get(i));
            }
        }
        return merged;
    }

    /**
     * Merges multiple AbstractMultiMap&lt;?, ?&gt; instances instances of potentially different key/value types into
     * a new AbstractMultiMap&lt;String, String&gt; by converting all keys and values to strings.
     */
    public static AbstractMultiMap<String, String> mergeToString(AbstractMultiMap<?, ?>... multiMaps) {
        Objects.requireNonNull(multiMaps, "multiMaps array cannot be null");
        AbstractMultiMap<String, String> merged = new AbstractMultiMap<String, String>() { };
        for (AbstractMultiMap<?, ?> map : multiMaps) {
            if (map == null) {
                continue;
            }
            for (int i = 0; i < map.keys.size(); i++) {
                merged.keys.add(String.valueOf(map.keys.get(i)));
                merged.values.add(String.valueOf(map.values.get(i)));
            }
        }
        return merged;
    }
}