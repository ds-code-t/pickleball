package io.pickleball.mapandStateutilities;

public interface StringBasedAccess<K, V> extends IndexedAccess<K, V> {
    V getValueByString(String keyWithIndex);
    V getValueByStringOrDefault(String keyWithIndex, Object defaultValue);
    K getKeyByString(String valueWithIndex);
    K getKeyByStringOrDefault(String valueWithIndex, Object defaultValue);
}