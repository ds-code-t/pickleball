package io.pickleball.mapandStateutilities;

public interface IndexedAccess<K, V> extends MultiMapOperations<K, V> {
    V getValueByKeyIndex(K key, int index);
    V getValueByKeyIndexSafe(K key, int index);
    K getKeyByValueIndex(V value, int index);
    K getKeyByValueIndexSafe(V value, int index);

    V getLastValue(K key);
    V getLastValueOrDefault(K key, Object defaultValue);
    K getLastKey(V value);
    K getLastKeyOrDefault(V value, Object defaultValue);

    V getFirstValue(K key);
    V getFirstValueOrDefault(K key, Object defaultValue);
    K getFirstKey(V value);
    K getFirstKeyOrDefault(V value, Object defaultValue);
}