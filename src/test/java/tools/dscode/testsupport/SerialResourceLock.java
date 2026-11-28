package tools.dscode.testsupport;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;

/**
 * Holds {@link SerialResource} from before-all until after-all so every method in the class
 * shares the exclusion with other workers.
 */
public final class SerialResourceLock implements BeforeAllCallback, AfterAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(SerialResourceLock.class);

    @Override
    public void beforeAll(ExtensionContext context) throws IOException {
        SerialResource resource = context.getRequiredTestClass().getAnnotation(SerialResource.class);
        if (resource == null || resource.value().isBlank()) return;
        SerialFileLock lock = SerialFileLock.acquire(SerialFileLock.directory(), resource.value());
        context.getStore(NAMESPACE).put("lock", lock);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        SerialFileLock lock = context.getStore(NAMESPACE).remove("lock", SerialFileLock.class);
        if (lock != null) lock.close();
    }
}
