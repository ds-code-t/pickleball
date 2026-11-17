package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.StoreFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replaces {@code StoreJournalReleaseTest}. The store waits on SQLite files, not a server journal. */
class StoreFileReleaseTest {
    @TempDir
    Path project;

    @Test
    void storeFilesAreTheSqliteDatabase() {
        assertEquals(project.resolve(".pickleball/store/main.db").normalize(),
                StorePaths.mainDatabase(project).normalize());
        assertEquals(project.resolve(".pickleball/store/pending-delete").normalize(),
                StoreFiles.pendingDelete(project).normalize());
    }

    @Test
    void waitsUntilTheHolderLetsGo() throws IOException {
        Path database = database();
        AtomicInteger calls = new AtomicInteger();
        long started = System.nanoTime();
        boolean released = StoreFiles.awaitRelease(database, 5_000, file -> {
            if (calls.incrementAndGet() < 4) throw new IOException("in use by another process");
        });
        long waited = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(released);
        assertEquals(4, calls.get());
        assertTrue(waited >= 250, "returned before the holder let go: " + waited + " ms");
    }

    @Test
    void givesUpAtTheBoundWithoutThrowing() throws IOException {
        Path database = database();
        long started = System.nanoTime();
        boolean released = StoreFiles.awaitRelease(database, 400, file -> {
            throw new IOException("in use by another process");
        });
        long waited = (System.nanoTime() - started) / 1_000_000L;
        assertFalse(released);
        assertTrue(waited >= 400 && waited < 5_000, "wait was not bounded: " + waited + " ms");
        assertTrue(Files.isRegularFile(database));
    }

    @Test
    void absentFileAndFreeFileReturnAtOnce() throws IOException {
        assertTrue(StoreFiles.awaitRelease(StorePaths.mainDatabase(project), 15_000, file -> {
            throw new IOException("never called");
        }));
        Path database = database();
        assertTrue(StoreFiles.awaitRelease(database, 15_000, StoreFiles::renameProbe));
        assertTrue(Files.isRegularFile(database), "the rename probe must put the database back");
        assertFalse(Files.exists(database.resolveSibling("main.db.release-check")));
        assertEquals(15_000L, StoreFiles.RELEASE_MS);
    }

    @Test
    void heldFileMarksPendingDelete() throws IOException {
        Path database = database();
        Path marker = StoreFiles.pendingDelete(project);
        assertFalse(StoreFiles.releaseFiles(java.util.List.of(database), marker, 200, file -> {
            throw new IOException("in use by another process");
        }));
        assertTrue(Files.isRegularFile(marker), "a held database must mark pending-delete");
        String text = Files.readString(marker);
        assertTrue(text.contains("main.db"), text);
        assertTrue(StoreFiles.releaseFiles(java.util.List.of(database), marker, 15_000, StoreFiles::renameProbe));
        assertFalse(Files.exists(marker), "a later release clears pending-delete");
        assertTrue(Files.isRegularFile(database));
    }

    @Test
    void releaseProjectFreesWalAndShm() throws IOException {
        Path database = database();
        Files.writeString(Path.of(database.toString() + "-wal"), "w");
        Files.writeString(Path.of(database.toString() + "-shm"), "s");
        Path runState = StorePaths.runState(project);
        Files.writeString(runState, "r");
        assertTrue(StoreFiles.releaseProject(project, 15_000));
        assertFalse(Files.exists(StoreFiles.pendingDelete(project)));
        assertTrue(Files.isRegularFile(database));
        assertTrue(Files.isRegularFile(Path.of(database.toString() + "-wal")));
        assertTrue(Files.isRegularFile(Path.of(database.toString() + "-shm")));
        assertTrue(Files.isRegularFile(runState));
    }

    private Path database() throws IOException {
        Path database = StorePaths.mainDatabase(project);
        Files.createDirectories(database.getParent());
        Files.writeString(database, "j");
        return database;
    }
}
