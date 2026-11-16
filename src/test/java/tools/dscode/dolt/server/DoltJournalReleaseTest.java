package tools.dscode.dolt.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code DoltServer.stop} waits, bounded, for Windows to release the store journal. */
class DoltJournalReleaseTest {
    @TempDir
    Path project;

    private Path journal() throws IOException {
        Path journal = DoltServer.journal(project);
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "j");
        return journal;
    }

    @Test
    void journalPathIsTheStoreJournal() {
        assertEquals(project.resolve(".pickleball/dolt/data/pickleball/.dolt/noms/journal.idx").normalize(),
                DoltServer.journal(project).normalize());
    }

    @Test
    void waitsUntilTheHolderLetsGo() throws IOException {
        Path journal = journal();
        AtomicInteger calls = new AtomicInteger();
        long started = System.nanoTime();
        boolean released = DoltServer.awaitJournalRelease(journal, 5_000, file -> {
            if (calls.incrementAndGet() < 4) throw new IOException("in use by another process");
        });
        long waited = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(released);
        assertEquals(4, calls.get());
        assertTrue(waited >= 250, "returned before the holder let go: " + waited + " ms");
    }

    @Test
    void givesUpAtTheBoundWithoutThrowing() throws IOException {
        Path journal = journal();
        long started = System.nanoTime();
        boolean released = DoltServer.awaitJournalRelease(journal, 400, file -> {
            throw new IOException("in use by another process");
        });
        long waited = (System.nanoTime() - started) / 1_000_000L;
        assertFalse(released);
        assertTrue(waited >= 400 && waited < 5_000, "wait was not bounded: " + waited + " ms");
        assertTrue(Files.isRegularFile(journal));
    }

    @Test
    void absentJournalAndFreeJournalReturnAtOnce() throws IOException {
        assertTrue(DoltServer.awaitJournalRelease(DoltServer.journal(project), 15_000, file -> {
            throw new IOException("never called");
        }));
        Path journal = journal();
        assertTrue(DoltServer.awaitJournalRelease(journal, 15_000, DoltServer::journalFree));
        assertTrue(Files.isRegularFile(journal), "the rename probe must put the journal back");
        assertFalse(Files.exists(journal.resolveSibling("journal.idx.release-check")));
        assertEquals(15_000L, DoltServer.JOURNAL_RELEASE_MS);
    }
}
