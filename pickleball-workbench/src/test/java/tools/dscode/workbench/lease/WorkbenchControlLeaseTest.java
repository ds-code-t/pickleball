package tools.dscode.workbench.lease;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchControlLeaseTest {

    @Test
    void humanHoldsByDefaultAndAgentMutationsFailUntilRequestControl() {
        WorkbenchControlLease lease = new WorkbenchControlLease();
        assertTrue(lease.snapshot().humanHolds());

        IllegalStateException denied = assertThrows(
                IllegalStateException.class,
                () -> WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, lease::requireMutatingAccess)
        );
        assertTrue(denied.getMessage().contains("workbench_request_control"));

        WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> lease.requestControl("Copilot"));
        assertTrue(lease.snapshot().agentHolds());
        assertEquals("Copilot", lease.snapshot().agentDisplayName());
        WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, lease::requireMutatingAccess);

        IllegalStateException humanLocked = assertThrows(IllegalStateException.class, lease::requireMutatingAccess);
        assertTrue(humanLocked.getMessage().contains("Take control"));
    }

    @Test
    void takeControlReturnsHumanUnlocksAndFailsInFlightPermissionWaits() throws Exception {
        WorkbenchControlLease lease = new WorkbenchControlLease();
        lease.attachUi();
        WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> {
            lease.requestControl("Copilot");
            lease.setCurrentAction("Playing the live scenario.");
        });
        assertTrue(lease.snapshot().bannerText().contains("Copilot"));
        assertTrue(lease.snapshot().bannerText().contains("Playing the live scenario."));

        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> {
                    waiting.countDown();
                    lease.awaitPermission(new WorkbenchPermissionRequest(
                            "perm-1",
                            WorkbenchPermissionKind.SAVE,
                            "Copy these live steps into file demo.feature / scenario Demo?",
                            Path.of("demo.feature").toString(),
                            "Demo"
                    ));
                });
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                finished.countDown();
            }
        }, "lease-permission-wait");
        waiter.setDaemon(true);
        waiter.start();

        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        waitUntil(() -> lease.snapshot().pending().isPresent(), 2_000);
        lease.takeControl();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertTrue(failure.get() instanceof WorkbenchPermissionCancelledException);
        assertTrue(lease.snapshot().humanHolds());
        assertTrue(lease.snapshot().pending().isEmpty());
    }

    @Test
    void allowAndDenyCompletePermissionWaits() throws Exception {
        WorkbenchControlLease lease = new WorkbenchControlLease();
        lease.attachUi();
        WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> lease.requestControl("Copilot"));

        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<WorkbenchPermissionDecision> decision = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            decision.set(WorkbenchCallContext.callAs(WorkbenchLeaseHolder.AGENT, () -> {
                waiting.countDown();
                return lease.awaitPermission(new WorkbenchPermissionRequest(
                        "perm-2",
                        WorkbenchPermissionKind.SAVE,
                        "Copy?",
                        Path.of("a.feature").toString(),
                        "A"
                ));
            }));
        }, "lease-allow");
        waiter.setDaemon(true);
        waiter.start();
        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        waitUntil(() -> lease.snapshot().pending().isPresent(), 2_000);
        lease.answerPermission("perm-2", false);
        waiter.join(2_000);
        assertEquals(WorkbenchPermissionDecision.DENY, decision.get());
    }

    @Test
    void headlessPermissionIsGrantedByTheExplicitToolCall() {
        WorkbenchControlLease lease = new WorkbenchControlLease();
        WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> lease.requestControl("stdio"));
        WorkbenchPermissionDecision decision = WorkbenchCallContext.callAs(
                WorkbenchLeaseHolder.AGENT,
                () -> lease.awaitPermission(new WorkbenchPermissionRequest(
                        "perm-3",
                        WorkbenchPermissionKind.SAVE,
                        "Copy?",
                        Path.of("a.feature").toString(),
                        "A"
                ))
        );
        assertEquals(WorkbenchPermissionDecision.ALLOW, decision);
        assertFalse(lease.snapshot().uiAttached());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for lease condition.");
            }
            Thread.sleep(10);
        }
    }
}
