package io.pickleball.mapandStateutilities;

import java.util.List;

public interface MultiMapOperations<K, V> {
    V put(K key, V value);  // Changed return type from void to V to match Map interface
    List<V> getValues(K key);
    List<K> getKeys(V value);
}
