package tools.dscode.testengine;

import org.junit.platform.engine.ConfigurationParameters;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class MergedConfigurationParameters implements ConfigurationParameters {

    private final ConfigurationParameters delegate;
    private final Map<String, String> overlay;

    MergedConfigurationParameters(ConfigurationParameters delegate, Map<String, String> overlay) {
        this.delegate = delegate;
        this.overlay = new LinkedHashMap<>(overlay);
    }

    @Override
    public Optional<String> get(String key) {
        String value = overlay.get(key);
        if (value != null) {
            return Optional.of(value);
        }
        return delegate.get(key);
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        String value = overlay.get(key);
        if (value != null) {
            return Optional.of(Boolean.parseBoolean(value));
        }
        return delegate.getBoolean(key);
    }

    @Override
    public Set<String> keySet() {
        Set<String> keys = new LinkedHashSet<>(delegate.keySet());
        keys.addAll(overlay.keySet());
        return keys;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int size() {
        return keySet().size();
    }

    @Override
    public String toString() {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String key : delegate.keySet()) {
            delegate.get(key).ifPresent(v -> merged.put(key, v));
        }
        merged.putAll(overlay);
        return merged.toString();
    }
}