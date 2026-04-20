package tools.dscode.testengine;

import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.EngineDiscoveryListener;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoverySelector;

import java.util.List;

final class DynamicEngineDiscoveryRequest implements EngineDiscoveryRequest {

    private final EngineDiscoveryRequest delegate;
    private final ConfigurationParameters configurationParameters;

    DynamicEngineDiscoveryRequest(EngineDiscoveryRequest delegate, ConfigurationParameters configurationParameters) {
        this.delegate = delegate;
        this.configurationParameters = configurationParameters;
    }

    @Override
    public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> selectorType) {
        return delegate.getSelectorsByType(selectorType);
    }

    @Override
    public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> filterType) {
        return delegate.getFiltersByType(filterType);
    }

    @Override
    public ConfigurationParameters getConfigurationParameters() {
        return configurationParameters;
    }

    @Override
    public EngineDiscoveryListener getDiscoveryListener() {
        return delegate.getDiscoveryListener();
    }
}