// src/main/java/tools/ds/modkit/BootstrapPlugin.java
package tools.ds.modkit;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.TestRunStarted;

public final class BootstrapPlugin implements ConcurrentEventListener {
    static  {
        EnsureInstalled.ensureOrDie();
    }
    @Override public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestRunStarted.class, e -> {
            System.err.println("[modkit] Cucumber plugin → attach");

        });
    }
}
