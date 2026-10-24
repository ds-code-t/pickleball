package com.example.pickleball;

import io.cucumber.java.en.Given;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;

/**
 * Prints from the scenario thread and from a thread that is not
 * {@code TestCase.run}. The text never contains a Cucumber {@code Scenario:} line.
 * A pool created before the scenario runs the task under the scenario name.
 */
public final class ConsoleSplitSteps {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "console-split-pool");
        thread.setDaemon(true);
        return thread;
    });

    @Given("^PRINT CONSOLE PROBE (PEND|CASE|WIN)$")
    public void printConsoleProbe(String token) throws Exception {
        String purpose = System.getProperty("pkb_run_purpose");
        System.out.println("probe-" + token + "-purpose=" + purpose);
        logInfo("probe-" + token + "-loginfo");
        System.out.printf("probe-%s-printf%n", token);
        System.out.println(("probe-" + token + "-chars").toCharArray());
        byte[] bytes = ("probe-" + token + "-bytes\n").getBytes(StandardCharsets.UTF_8);
        System.out.write(bytes, 0, bytes.length);
        System.out.print("probe-" + token + "-multi\nprobe-" + token + "-still\n");

        Thread child = new Thread(() -> System.out.println("probe-" + token + "-child"), "console-split-child");
        child.start();
        child.join(5000);

        POOL.submit(tools.dscode.launcher.WorkbenchConsoleTap.carrying(() -> {
            System.out.println("probe-" + token + "-pool");
            return null;
        })).get(5, TimeUnit.SECONDS);
    }
}
