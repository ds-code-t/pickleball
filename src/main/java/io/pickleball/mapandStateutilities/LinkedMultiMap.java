// LinkedMultiMap.java
package io.pickleball.mapandStateutilities;

import java.util.*;

public class LinkedMultiMap<K, V> extends AbstractMultiMap<K, V> implements StringBasedAccess<K, V> {

    private static class ParsedString {
        final String base;
        final int index;

        ParsedString(String base, int index) {
            this.base = base;
            this.index = index;
        }
    }

    public LinkedMultiMap() {
        super();
    }

    public LinkedMultiMap(List<K> keys, List<V> values) {
        super(keys, values);
    }

    @Override
    public V get(Object key) {
        return isStringKey(key) ?
                getValueByString((String) key) :
                getLastValueOrDefault((K) key, null);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return isStringKey(key) ?
                getValueByStringOrDefault((String) key, defaultValue) :
                getLastValueOrDefault((K) key, defaultValue);
    }

    private boolean isStringKey(Object key) {
        return key instanceof String && determineKeyType() == String.class;
    }

    private Class<?> determineKeyType() {
        if (!keys.isEmpty()) {
            K firstKey = keys.get(0);
            return firstKey != null ? firstKey.getClass() : Object.class;
        }
        return Object.class;
    }

    @Override
    public V getValueByKeyIndex(K key, int index) {
        List<V> vals = getValues(key);
        return vals.get(index);
    }

    @Override
    public V getValueByKeyIndexSafe(K key, int index) {
        List<V> vals = getValues(key);
        if (index < 0) {
            index = vals.size() + index;
        }
        return (index < 0 || index >= vals.size()) ? null : vals.get(index);
    }

    @Override
    public K getKeyByValueIndex(V value, int index) {
        List<K> ks = getKeys(value);
        return ks.get(index);
    }

    @Override
    public K getKeyByValueIndexSafe(V value, int index) {
        List<K> ks = getKeys(value);
        if (index < 0) {
            index = ks.size() + index;
        }
        return (index < 0 || index >= ks.size()) ? null : ks.get(index);
    }

    @Override
    public V getLastValue(K key) {
        List<V> vs = getValues(key);
        return vs.get(vs.size() - 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V getLastValueOrDefault(K key, Object defaultValue) {
        List<V> vs = getValues(key);
        return vs.isEmpty() ? (V) defaultValue : vs.get(vs.size() - 1);
    }

    @Override
    public K getLastKey(V value) {
        List<K> ks = getKeys(value);
        return ks.get(ks.size() - 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public K getLastKeyOrDefault(V value, Object defaultValue) {
        List<K> ks = getKeys(value);
        return ks.isEmpty() ? (K) defaultValue : ks.get(ks.size() - 1);
    }

    @Override
    public V getFirstValue(K key) {
        List<V> vs = getValues(key);
        return vs.get(0);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V getFirstValueOrDefault(K key, Object defaultValue) {
        List<V> vs = getValues(key);
        return vs.isEmpty() ? (V) defaultValue : vs.get(0);
    }

    @Override
    public K getFirstKey(V value) {
        List<K> ks = getKeys(value);
        return ks.get(0);
    }

    @Override
    @SuppressWarnings("unchecked")
    public K getFirstKeyOrDefault(V value, Object defaultValue) {
        List<K> ks = getKeys(value);
        return ks.isEmpty() ? (K) defaultValue : ks.get(0);
    }

    private ParsedString parseIndexedString(String input) {
        if (input == null) return null;
        int hashPos = input.lastIndexOf('#');
        if (hashPos == -1) {
            return new ParsedString(input, -1);
        }
        String base = input.substring(0, hashPos).trim();
        String numberPart = input.substring(hashPos + 1).trim();
        if (base.isEmpty() || numberPart.isEmpty()) {
            return null;
        }
        try {
            int oneBasedIndex = Integer.parseInt(numberPart);
            if (oneBasedIndex < 1) oneBasedIndex += 1;
            return new ParsedString(base, oneBasedIndex - 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V getValueByString(String keyWithIndex) {
        ParsedString parsed = parseIndexedString(keyWithIndex);
        if (parsed == null) return null;
        K key = (K) parsed.base;
        return getValueByKeyIndexSafe(key, parsed.index);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V getValueByStringOrDefault(String keyWithIndex, Object defaultValue) {
        ParsedString parsed = parseIndexedString(keyWithIndex);
        if (parsed == null) return null;
        K key = (K) parsed.base;
        V result = getValueByKeyIndexSafe(key, parsed.index);
        return result != null ? result : (V) defaultValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K getKeyByString(String valueWithIndex) {
        ParsedString parsed = parseIndexedString(valueWithIndex);
        if (parsed == null) return null;
        V value = (V) parsed.base;
        return getKeyByValueIndexSafe(value, parsed.index);
    }

    @Override
    @SuppressWarnings("unchecked")
    public K getKeyByStringOrDefault(String valueWithIndex, Object defaultValue) {
        ParsedString parsed = parseIndexedString(valueWithIndex);
        if (parsed == null) return null;
        V value = (V) parsed.base;
        K result = getKeyByValueIndexSafe(value, parsed.index);
        return result != null ? result : (K) defaultValue;
    }

    public NavigableIterator<K, V> navigator() {
        return new LinkedMultiMapIterator<>(this);
    }

}