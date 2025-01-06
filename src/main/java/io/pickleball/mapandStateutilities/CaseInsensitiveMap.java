package io.pickleball.mapandStateutilities;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public class CaseInsensitiveMap<K, V> extends HashMap<String, V> {
    @Override
    public V put(String key, V value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.put(key.toUpperCase(), value);
    }

    public V putGeneric(K key, V value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.put(key.toString().toUpperCase(), value);
    }

    @Override
    public V get(Object key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.get(key.toString().toUpperCase());
    }

    @Override
    public boolean containsKey(Object key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.containsKey(key.toString().toUpperCase());
    }

    @Override
    public V remove(Object key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.remove(key.toString().toUpperCase());
    }

    @Override
    public void putAll(Map<? extends String, ? extends V> map) {
        if (map == null) {
            throw new NullPointerException("Map cannot be null");
        }
        map.forEach(this::put);
    }

    public void putAllGeneric(Map<? extends K, ? extends V> map) {
        if (map == null) {
            throw new NullPointerException("Map cannot be null");
        }
        map.forEach(this::putGeneric);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.getOrDefault(key.toString().toUpperCase(), defaultValue);
    }

    @Override
    public V replace(String key, V value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.replace(key.toUpperCase(), value);
    }

    public V replaceGeneric(K key, V value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.replace(key.toString().toUpperCase(), value);
    }

    @Override
    public boolean replace(String key, V oldValue, V newValue) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.replace(key.toUpperCase(), oldValue, newValue);
    }

    public boolean replaceGeneric(K key, V oldValue, V newValue) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.replace(key.toString().toUpperCase(), oldValue, newValue);
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super V> action) {
        super.forEach(action);
    }

    // Additional utility method to support generic type iteration
    public void forEachGeneric(BiConsumer<? super K, ? super V> action) {
        if (action == null) {
            throw new NullPointerException("Action cannot be null");
        }
        // Note: This will pass the uppercase String key as K type, which might not be ideal
        // but maintains consistency with the map's internal structure
        super.forEach((key, value) -> action.accept((K)key, value));
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.remove(key.toString().toUpperCase(), value);
    }

    @Override
    public V computeIfAbsent(String key, Function<? super String, ? extends V> mappingFunction) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.computeIfAbsent(key.toUpperCase(), mappingFunction);
    }

    @Override
    public V computeIfPresent(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.computeIfPresent(key.toUpperCase(), remappingFunction);
    }

    @Override
    public V compute(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.compute(key.toUpperCase(), remappingFunction);
    }

    @Override
    public V merge(String key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return super.merge(key.toUpperCase(), value, remappingFunction);
    }
}