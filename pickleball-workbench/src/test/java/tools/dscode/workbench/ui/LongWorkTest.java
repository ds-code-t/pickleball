package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.Dimension;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongWorkTest {
    @Test
    void longLoadsRunOffTheCallerThreadAndNameTheStatus() throws Exception {
        LongWork work = new LongWork();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> worker = new AtomicReference<>();
        AtomicReference<String> statusWhileApplying = new AtomicReference<>();

        work.start(
                LongWork.SYNCING_THE_PROJECT,
                () -> {
                    worker.set(Thread.currentThread().getName());
                    started.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to finish the load");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return "done";
                },
                value -> {
                    statusWhileApplying.set(work.status());
                    assertFalse(work.failed());
                    work.finish();
                    finished.countDown();
                },
                failure -> finished.countDown(),
                command -> LongWork.thread(command).start(),
                Runnable::run
        );

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals("pickleball-workbench-load", worker.get());
        assertFalse(worker.get().equals(Thread.currentThread().getName()));
        assertEquals(LongWork.SYNCING_THE_PROJECT, work.status());
        assertFalse(work.failed());
        release.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(LongWork.SYNCING_THE_PROJECT, statusWhileApplying.get());
        assertNull(work.status());
        assertEquals("pickleball-workbench-load", LongWork.thread(() -> { }).getName());
    }

    @Test
    void aFailureStaysVisibleAndNamesTheError() throws Exception {
        LongWork work = new LongWork();
        CountDownLatch finished = new CountDownLatch(1);
        work.start(
                LongWork.STARTING_THE_RUN,
                () -> {
                    throw new IllegalStateException("port in use");
                },
                value -> finished.countDown(),
                failure -> finished.countDown(),
                command -> LongWork.thread(command).start(),
                Runnable::run
        );
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertTrue(work.failed());
        assertEquals("Starting the run failed: port in use", work.status());
    }

    @Test
    void splitPanesDropTheirMinimumSoEitherSideCanTakeTheWindow() {
        JPanel left = new JPanel();
        left.setMinimumSize(new Dimension(400, 320));
        JPanel right = new JPanel();
        right.setMinimumSize(new Dimension(500, 320));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        WorkbenchTheme.styleSplit(split);
        assertEquals(new Dimension(0, 0), left.getMinimumSize());
        assertEquals(new Dimension(0, 0), right.getMinimumSize());
        assertEquals(0, split.getMinimumSize().width);
        assertEquals(0, split.getMinimumSize().height);
        assertTrue(split.isContinuousLayout());
    }
}
