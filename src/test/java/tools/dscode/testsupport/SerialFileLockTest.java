package tools.dscode.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialFileLockTest {
    @TempDir
    Path directory;

    @Test
    void aHeldNameBlocksAndAnotherNameDoesNot() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (SerialFileLock held = SerialFileLock.acquire(directory, "cwd-cli-session")) {
            assertNull(SerialFileLock.tryAcquire(directory, "cwd-cli-session"));
            try (SerialFileLock other = SerialFileLock.tryAcquire(directory, "other-port")) {
                assertNotNull(other);
            }
            Future<SerialFileLock> otherThread = pool.submit(
                    () -> SerialFileLock.tryAcquire(directory, "cwd-cli-session"));
            assertNull(otherThread.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        try (SerialFileLock again = SerialFileLock.tryAcquire(directory, "cwd-cli-session")) {
            assertNotNull(again);
        }
    }

    @Test
    void aFileLockHeldOutsideTheGateBlocksTheSameName() throws Exception {
        Path file = directory.resolve("cwd-cli-session.lock");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertNull(SerialFileLock.tryAcquire(directory, "cwd-cli-session"));
        }
        try (SerialFileLock again = SerialFileLock.tryAcquire(directory, "cwd-cli-session")) {
            assertNotNull(again);
        }
    }

    @Test
    void annotationRegistersTheLockExtension() {
        ExtendWith extend = SerialResource.class.getAnnotation(ExtendWith.class);
        assertNotNull(extend);
        assertTrue(extend.value().length == 1);
        assertTrue(extend.value()[0] == SerialResourceLock.class);
    }
}
