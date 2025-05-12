package io.pickleball.cacheandstate;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.util.List;

public interface Registerable {
    // ThreadLocal Multimap to store lists of instances per thread
    ThreadLocal<Multimap<String, Registerable>> INSTANCES = ThreadLocal.withInitial(ArrayListMultimap::create);

    // Default method to register instance with modified key
    default void register(String key) {
        String modifiedKey = key + "_" + getRegisterableName();
        Multimap<String, Registerable> multimap = INSTANCES.get();
        // Prevent duplicate instance for same key
        if (!multimap.get(modifiedKey).contains(this)) {
            multimap.put(modifiedKey, this);
        }

        String registeredKey = "Registered_" + key;
        // Also register under "Registered_" + key
        if (!multimap.get(registeredKey).contains(this)) {
            multimap.put(registeredKey, this);
        }
    }

    // Default method to get runtime class simple name
    default String getRegisterableName() {
        return this.getClass().getSimpleName();
    }

    // Static method to access registered instances for current thread
    static Multimap<String, Registerable> getInstances() {
        return INSTANCES.get();
    }

    // Static method to retrieve last instance by key for current thread
    static Registerable getByKey(String key) {
        List<Registerable> instances = (List<Registerable>) INSTANCES.get().get(key);
        return instances.isEmpty() ? null : instances.get(instances.size() - 1);
    }

    // Overloaded method to retrieve instance by key and index
    static Registerable getByKey(String key, int index) {
        List<Registerable> instances = (List<Registerable>) INSTANCES.get().get(key);
        return (index >= 0 && index < instances.size()) ? instances.get(index) : null;
    }
}