//package io.pickleball.mapandStateutilities;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Objects;
//
///**
// * A custom data structure that behaves like a linked multimap.
// * Entries maintain insertion order. Duplicate keys and values are allowed.
// * <p>
// * Internally it stores keys and values in parallel ArrayLists.
// * The position in both lists represents the entry association.
// * <p>
// * This structure provides:
// * - put(key, value)
// * - getValues(key): all values associated with a given key in insertion order
// * - getKeys(value): all keys associated with a given value in insertion order
// * - getValueByKeyIndex(key, index): get the Nth value for a given key
// * - getKeyByValueIndex(value, index): get the Nth key for a given value
// * - Safe versions of these index-based getters that return null if out of range
// * - Versions of these index-based getters that accept strings like "key #2" meaning
// * "the second occurrence of that key".
// */
//public class LinkedMultiMap2<K, V> {
//    private final List<K> keys;
//    private final List<V> values;
//
//    public LinkedMultiMap2() {
//        this.keys = new ArrayList<>();
//        this.values = new ArrayList<>();
//    }
//
//    @Override
//    public String toString() {
//        StringBuilder sb = new StringBuilder("{");
//        for (int i = 0; i < keys.size(); i++) {
//            if (i > 0) {
//                sb.append(", ");
//            }
//            sb.append(keys.get(i))
//                    .append("=")
//                    .append(values.get(i) != null ? values.get(i) : "null");
//        }
//        sb.append("}");
//        return sb.toString();
//    }
//
//
//    // New constructor to initialize from lists directly
//    public LinkedMultiMap2(List<K> keys, List<V> values) {
//        if (keys.size() != values.size()) {
//            throw new IllegalArgumentException("Keys and values lists must have the same size.");
//        }
//        this.keys = new ArrayList<>(keys);
//        this.values = new ArrayList<>(values);
//    }
//
//    public void put(K key, V value) {
//        keys.add(key);
//        values.add(value);
//    }
//
//    public List<V> getValues(K key) {
//        List<V> result = new ArrayList<>();
//        for (int i = 0; i < keys.size(); i++) {
//            if (Objects.equals(keys.get(i), key)) {
//                result.add(values.get(i));
//            }
//        }
//        return result;
//    }
//
//    public List<K> getKeys(V value) {
//        List<K> result = new ArrayList<>();
//        for (int i = 0; i < values.size(); i++) {
//            if (Objects.equals(values.get(i), value)) {
//                result.add(keys.get(i));
//            }
//        }
//        return result;
//    }
//
//    /**
//     * Returns the Nth value associated with the given key (0-based index).
//     * Throws IndexOutOfBoundsException if index is invalid.
//     */
//    public V getValueByKeyIndex(K key, int index) {
//        List<V> vals = getValues(key);
//        return vals.get(index);
//    }
//
//    /**
//     * Safe version of getValueByKeyIndex.
//     * Returns null if index is out of range.
//     */
//    public V getValueByKeyIndexSafe(K key, int index) {
//        List<V> vals = getValues(key);
//        if (index < 0)
//            index = vals.size() + index;
//        if (index < 0 || index >= vals.size()) {
//            return null;
//        }
//        return vals.get(index);
//    }
//
//    public K getLastKeyOrDefault(V value, Object obj) {
//        List<K> ks = getKeys(value);
//        if (ks.size() == 0)
//            return (K) obj;
//        return ks.get(ks.size() - 1);
//    }
//
//    public K getLastKey(V value) {
//        List<K> ks = getKeys(value);
//        return ks.get(ks.size() - 1);
//    }
//
//    public V getLastValueOrDefault(K key, Object obj) {
//        List<V> vs = getValues(key);
//        if (vs.size() == 0)
//            return (V) obj;
//        return vs.get(vs.size() - 1);
//    }
//
//
//    public V getLastValue(K key) {
//        List<V> vs = getValues(key);
//        return vs.get(vs.size() - 1);
//    }
//
//    public K getFirstKeyOrDefault(V value, Object obj) {
//        List<K> ks = getKeys(value);
//        if (ks.size() == 0)
//            return (K) obj;
//        return ks.get(0);
//    }
//
//    public K getFirstKey(V value) {
//        List<K> ks = getKeys(value);
//        return ks.get(0);
//    }
//
//    public V getFirstValueOrDefault(K key, Object obj) {
//        List<V> vs = getValues(key);
//        if (vs.size() == 0)
//            return (V) obj;
//        return vs.get(0);
//    }
//
//    public V getFirstValue(K key) {
//        List<V> vs = getValues(key);
//        return vs.get(0);
//    }
//
//
//    /**
//     * Returns the Nth key associated with the given value (0-based index).
//     * Throws IndexOutOfBoundsException if index is invalid.
//     */
//    public K getKeyByValueIndex(V value, int index) {
//        List<K> ks = getKeys(value);
//        return ks.get(index);
//    }
//
//    /**
//     * Safe version of getKeyByValueIndex.
//     * Returns null if index is out of range.
//     */
//    public K getKeyByValueIndexSafe(V value, int index) {
//        List<K> ks = getKeys(value);
//        if (index < 0)
//            index = ks.size() + index;
//        if (index < 0 || index >= ks.size()) {
//            return null;
//        }
//        return ks.get(index);
//    }
//
//    /**
//     * Helper to parse strings like "myKey #2" where "#2" means "index = 1"
//     * (0-based) since #1 means the first occurrence (index 0).
//     * If format is invalid or the index portion is invalid, returns null.
//     */
//    private static class ParsedString {
//        String base;
//        int index;
//
//        ParsedString(String base, int index) {
//            this.base = base;
//            this.index = index;
//        }
//    }
//
//    private ParsedString parseIndexedString(String input) {
//        if (input == null) return null;
//        int hashPos = input.lastIndexOf('#');
//        if (hashPos == -1) {
//            return new ParsedString(input, -1);
//        }
//        String base = input.substring(0, hashPos).trim();
//        String numberPart = input.substring(hashPos + 1).trim();
//        if (base.isEmpty() || numberPart.isEmpty()) {
//            return null;
//        }
//        try {
//            int oneBasedIndex = Integer.parseInt(numberPart);
//            if (oneBasedIndex < 1)
//                oneBasedIndex += 1;
//            int zeroBasedIndex = oneBasedIndex - 1;
//            return new ParsedString(base, zeroBasedIndex);
//        } catch (NumberFormatException e) {
//            return null;
//        }
//    }
//
//    /**
//     * Given a string like "someKey #2", return the corresponding value.
//     * This uses getValueByKeyIndexSafe internally.
//     * Returns null if the format is invalid or out of range.
//     */
//    public V getValueByString(String keyWithIndex) {
//        ParsedString parsed = parseIndexedString(keyWithIndex);
//        if (parsed == null) return null;
//        @SuppressWarnings("unchecked")
//        K key = (K) parsed.base; // Assuming keys are strings or can be cast safely.
//        return getValueByKeyIndexSafe(key, parsed.index);
//    }
//
//    public V getValueByStringOrDefault(String keyWithIndex, Object obj) {
//        ParsedString parsed = parseIndexedString(keyWithIndex);
//        if (parsed == null) return null;
//        K key = (K) parsed.base; // Assuming keys are strings or can be cast safely.
//        V returnObj = getValueByKeyIndexSafe(key, parsed.index);
//        if (returnObj == null)
//            return (V) obj;
//        return returnObj;
//    }
//
//    /**
//     * Given a string like "someValue #3", return the corresponding key.
//     * This uses getKeyByValueIndexSafe internally.
//     * Returns null if the format is invalid or out of range.
//     */
//    public K getKeyByString(String valueWithIndex) {
//        ParsedString parsed = parseIndexedString(valueWithIndex);
//        if (parsed == null) return null;
//        @SuppressWarnings("unchecked")
//        V value = (V) parsed.base; // Assuming values are strings or can be cast safely.
//        return getKeyByValueIndexSafe(value, parsed.index);
//    }
//
//    public K getKeyByStringOrDefault(String valueWithIndex, Object obj) {
//        ParsedString parsed = parseIndexedString(valueWithIndex);
//        if (parsed == null) return null;
//        @SuppressWarnings("unchecked")
//        V value = (V) parsed.base; // Assuming values are strings or can be cast safely.
//        K returnObj = getKeyByValueIndexSafe(value, parsed.index);
//        if (returnObj == null)
//            return (K) obj;
//        return returnObj;
//    }
//}
