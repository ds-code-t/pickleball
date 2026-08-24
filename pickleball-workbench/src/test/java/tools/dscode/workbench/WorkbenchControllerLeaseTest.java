package tools.dscode.workbench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.lease.WorkbenchCallContext;
import tools.dscode.workbench.lease.WorkbenchLeaseHolder;
import tools.dscode.workbench.player.WorkbenchSaveResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchControllerLeaseTest {
    @TempDir
    Path project;

    @Test
    void agentMutatingCallsFailUntilTheyHoldTheLease() {
        try (WorkbenchController controller = new WorkbenchController(project)) {
            IllegalStateException denied = assertThrows(
                    IllegalStateException.class,
                    () -> WorkbenchCallContext.runAs(
                            WorkbenchLeaseHolder.AGENT,
                            () -> controller.replaceLiveDocument(List.of("Given stay"))
                    )
            );
            assertTrue(denied.getMessage().contains("workbench_request_control"));

            WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> controller.requestControl("Copilot"));
            WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> {
                controller.setCurrentAction("Editing the live buffer.");
                controller.replaceLiveDocument(List.of("Scenario: Live", "  Given stay"));
            });
            assertTrue(controller.playerState().documentText().contains("Given stay"));
            assertEquals("Copilot", controller.controlLeaseSnapshot().agentDisplayName());
        }
    }

    @Test
    void takeControlUnlocksHumanAndCancelsAgentSaveWaitWithoutWriting() throws Exception {
        Path feature = project.resolve("src/test/resources/features/keep.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Keep
                  Scenario: Original
                    Given original
                """);
        String original = Files.readString(feature);

        try (WorkbenchController controller = new WorkbenchController(project)) {
            controller.attachUi();
            controller.loadPickerScenario(
                    List.of("Feature: Keep", "", "Scenario: Original", "  Given original", "  And extra"),
                    feature,
                    "Original",
                    2,
                    3
            );
            WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> controller.requestControl("Copilot"));

            CountDownLatch waiting = new CountDownLatch(1);
            AtomicReference<WorkbenchSaveResult> result = new AtomicReference<>();
            Thread saver = new Thread(() -> {
                result.set(WorkbenchCallContext.callAs(WorkbenchLeaseHolder.AGENT, () -> {
                    waiting.countDown();
                    return controller.requestSave();
                }));
            }, "controller-save-wait");
            saver.setDaemon(true);
            saver.start();
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            waitUntil(() -> controller.controlLeaseSnapshot().pending().isPresent(), 2_000);

            controller.takeControl();
            saver.join(2_000);
            assertFalse(result.get().written());
            assertEquals("CANCELLED", result.get().status());
            assertEquals(original, Files.readString(feature));
            assertTrue(controller.controlLeaseSnapshot().humanHolds());
        }
    }

    @Test
    void deniedSaveDoesNotWriteAndApprovedSaveCopiesTheLiveScenario() throws Exception {
        Path feature = project.resolve("src/test/resources/features/login.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Sign in
                  Scenario: Valid password
                    Given a user
                """);

        try (WorkbenchController controller = new WorkbenchController(project)) {
            controller.attachUi();
            controller.loadPickerScenario(
                    List.of("Feature: Sign in", "", "Scenario: Valid password", "  Given a user", "  And extra"),
                    feature,
                    "Valid password",
                    2,
                    3
            );
            WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> controller.requestControl("Copilot"));

            CountDownLatch waiting = new CountDownLatch(1);
            AtomicReference<WorkbenchSaveResult> denied = new AtomicReference<>();
            Thread saver = new Thread(() -> {
                denied.set(WorkbenchCallContext.callAs(WorkbenchLeaseHolder.AGENT, () -> {
                    waiting.countDown();
                    return controller.requestSave();
                }));
            }, "controller-save-deny");
            saver.setDaemon(true);
            saver.start();
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            waitUntil(() -> controller.controlLeaseSnapshot().pending().isPresent(), 2_000);
            String requestId = controller.controlLeaseSnapshot().pendingPermission().id();
            controller.answerPermission(requestId, false);
            saver.join(2_000);
            assertEquals("DENIED", denied.get().status());
            assertFalse(Files.readString(feature).contains("And extra"));

            CountDownLatch waitingAllow = new CountDownLatch(1);
            AtomicReference<WorkbenchSaveResult> allowed = new AtomicReference<>();
            Thread allowSaver = new Thread(() -> {
                allowed.set(WorkbenchCallContext.callAs(WorkbenchLeaseHolder.AGENT, () -> {
                    waitingAllow.countDown();
                    return controller.requestSave();
                }));
            }, "controller-save-allow");
            allowSaver.setDaemon(true);
            allowSaver.start();
            assertTrue(waitingAllow.await(2, TimeUnit.SECONDS));
            waitUntil(() -> controller.controlLeaseSnapshot().pending().isPresent(), 2_000);
            controller.answerPermission(controller.controlLeaseSnapshot().pendingPermission().id(), true);
            allowSaver.join(2_000);
            assertTrue(allowed.get().written());
            assertTrue(Files.readString(feature).contains("And extra"));
        }
    }

    @Test
    void demoCommitSaveStaysUnsavable() {
        try (WorkbenchController controller = new WorkbenchController(project)) {
            WorkbenchSaveResult result = controller.commitSave();
            assertFalse(result.written());
            assertEquals("UNSAVABLE", result.status());
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for controller lease condition.");
            }
            Thread.sleep(10);
        }
    }
}
