package tools.dscode.launcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.ConsoleAppender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.BasicMDCAdapter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchConsoleTapTest {
    @AfterEach
    void reset() {
        WorkbenchConsoleTap.resetForTest();
    }

    @Test
    void normalPrintIsByteIdenticalWhenTheTapIsNotInstalled() {
        String previous = System.getProperty("pkb_run_purpose");
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            System.clearProperty("pkb_run_purpose");
            WorkbenchConsoleTap.installIfActive();
            assertSame(out, System.out);
            assertSame(err, System.err);
            assertFalse(WorkbenchConsoleTap.active());

            ByteArrayOutputStream rawSink = new ByteArrayOutputStream();
            ByteArrayOutputStream wrappedSink = new ByteArrayOutputStream();
            PrintStream raw = new PrintStream(rawSink, true, StandardCharsets.UTF_8);
            PrintStream wrapped = WorkbenchConsoleTap.wrap(new PrintStream(wrappedSink, true, StandardCharsets.UTF_8));
            exercise(raw);
            exercise(wrapped);
            assertEquals(rawSink.toString(StandardCharsets.UTF_8), wrappedSink.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
            restore(previous);
        }
    }

    @Test
    void logbackAppenderStartedBeforeTheTapStillMarksTheScenario() throws Exception {
        String previous = System.getProperty("pkb_run_purpose");
        PrintStream out = System.out;
        PrintStream err = System.err;
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new BasicMDCAdapter());
        try {
            System.setProperty("pkb_run_purpose", "workbench-discover");
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream raw = new PrintStream(sink, true, StandardCharsets.UTF_8);
            System.setOut(raw);

            Logger logger = consoleLogger(context);
            logger.info("before-tap");

            WorkbenchConsoleTap.install();
            WorkbenchConsoleTap.retargetLogbackContext(context);
            WorkbenchConsoleTap.enter("Pend");
            try {
                logger.info("hello-from-logback");
            } finally {
                WorkbenchConsoleTap.leave();
            }
            String text = sink.toString(StandardCharsets.UTF_8);
            assertMarked(text, "Pend", "hello-from-logback", "before-tap");

            Path dir = Files.createTempDirectory("pkb-console");
            WorkbenchRunReports.saveConsole(dir, List.of("Pend", "Pend case"), text);
            String file = Files.readString(dir.resolve("console").resolve("Pend.txt"));
            assertTrue(file.contains("hello-from-logback"), file);
            assertFalse(file.contains("# pkb-console"), file);
            assertFalse(file.contains("before-tap"), file);
            String unassigned = Files.readString(dir.resolve("console").resolve("unassigned.txt"));
            assertTrue(unassigned.contains("before-tap"), unassigned);
            assertFalse(unassigned.contains("hello-from-logback"), unassigned);
        } finally {
            context.stop();
            System.setOut(out);
            System.setErr(err);
            restore(previous);
        }
    }

    @Test
    void log4jConsoleAppenderStartedBeforeTheTapStillMarksTheScenario() throws Exception {
        String previous = System.getProperty("pkb_run_purpose");
        PrintStream out = System.out;
        PrintStream err = System.err;
        org.apache.logging.log4j.core.LoggerContext context = null;
        try {
            System.setProperty("pkb_run_purpose", "workbench-discover");
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream raw = new PrintStream(sink, true, StandardCharsets.UTF_8);
            System.setOut(raw);

            ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
            builder.setStatusLevel(org.apache.logging.log4j.Level.ERROR);
            builder.setConfigurationName("pkb-console-tap");
            builder.add(builder.newAppender("probe", "Console")
                    .addAttribute("target", "SYSTEM_OUT")
                    .add(builder.newLayout("PatternLayout").addAttribute("pattern", "%m%n")));
            builder.add(builder.newLogger("probe", org.apache.logging.log4j.Level.INFO)
                    .add(builder.newAppenderRef("probe"))
                    .addAttribute("additivity", false));
            context = Configurator.initialize(builder.build());
            org.apache.logging.log4j.Logger logger = LogManager.getLogger("probe");
            logger.info("before-tap-log4j");

            WorkbenchConsoleTap.install();
            WorkbenchConsoleTap.enter("Pend case");
            try {
                logger.info("hello-from-log4j");
            } finally {
                WorkbenchConsoleTap.leave();
            }
            String text = sink.toString(StandardCharsets.UTF_8);
            assertMarked(text, "Pend case", "hello-from-log4j", "before-tap-log4j");
        } finally {
            if (context != null) Configurator.shutdown(context);
            System.setOut(out);
            System.setErr(err);
            restore(previous);
        }
    }

    @Test
    void otherThreadWritePathsAndParallelMultilineStayWithTheScenario() throws Exception {
        String previous = System.getProperty("pkb_run_purpose");
        PrintStream out = System.out;
        PrintStream err = System.err;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            System.setProperty("pkb_run_purpose", "workbench-confirm");
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream wrapped = WorkbenchConsoleTap.wrap(new PrintStream(sink, true, StandardCharsets.UTF_8));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            var pend = pool.submit(task("Pend", wrapped, ready, go));
            var longer = pool.submit(task("Pend case", wrapped, ready, go));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pend.get(10, TimeUnit.SECONDS);
            longer.get(10, TimeUnit.SECONDS);

            WorkbenchConsoleTap.enter("C:\\Users\\Dan She'll\\page");
            try {
                Thread child = new Thread(() -> {
                    wrapped.println("child-windows");
                    wrapped.printf("fmt %s%n", "windows");
                    wrapped.println("CHAR windows".toCharArray());
                    byte[] bytes = "byte windows\n".getBytes(StandardCharsets.UTF_8);
                    wrapped.write(bytes, 0, bytes.length);
                });
                child.start();
                child.join(5000);
                wrapped.print("multi windows\nstill windows\n");
            } finally {
                WorkbenchConsoleTap.leave();
            }

            String text = sink.toString(StandardCharsets.UTF_8);
            assertFalse(text.contains("Scenario:"), text);
            Path dir = Files.createTempDirectory("pkb-console-parallel");
            WorkbenchRunReports.saveConsole(
                    dir,
                    List.of("Pend", "Pend case", "C:\\Users\\Dan She'll\\page"),
                    text
            );
            Path console = dir.resolve("console");
            String pendFile = Files.readString(console.resolve("Pend.txt"));
            String caseFile = Files.readString(console.resolve("Pend_case.txt"));
            String windows = Files.readString(console.resolve("C_Users_Dan_She_ll_page.txt"));
            assertTrue(pendFile.contains("L1-Pend"), pendFile);
            assertTrue(pendFile.contains("L2-Pend"), pendFile);
            assertFalse(pendFile.contains("Pend case"), pendFile);
            assertFalse(pendFile.contains("# pkb-console"), pendFile);
            assertTrue(caseFile.contains("L1-Pend case"), caseFile);
            assertTrue(caseFile.contains("L2-Pend case"), caseFile);
            assertFalse(caseFile.contains("L1-Pend\n") || caseFile.contains("L1-Pend\r"), caseFile);
            assertTrue(windows.contains("child-windows"), windows);
            assertTrue(windows.contains("fmt windows"), windows);
            assertTrue(windows.contains("CHAR windows"), windows);
            assertTrue(windows.contains("byte windows"), windows);
            assertTrue(windows.contains("multi windows"), windows);
            assertTrue(windows.contains("still windows"), windows);
            assertFalse(windows.contains("# pkb-console"), windows);
            assertFalse(Files.exists(console.resolve("unassigned.txt")));
        } finally {
            pool.shutdownNow();
            System.setOut(out);
            System.setErr(err);
            restore(previous);
        }
    }

    private static void assertMarked(String text, String scenario, String payload, String before) {
        String prefix = WorkbenchConsoleTap.mark(scenario);
        boolean sawPayload = false;
        boolean sawBefore = false;
        for (String line : text.split("\\R", -1)) {
            if (line.startsWith(prefix) && line.contains(payload)) sawPayload = true;
            if (line.contains(before)) {
                sawBefore = true;
                assertFalse(line.contains(prefix), line);
            }
        }
        assertTrue(sawPayload, text);
        assertTrue(sawBefore, text);
    }

    private static Runnable task(String name, PrintStream wrapped, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            WorkbenchConsoleTap.enter(name);
            try {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("late");
                wrapped.print("L1-" + name + "\nL2-" + name + "\n");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            } finally {
                WorkbenchConsoleTap.leave();
            }
        };
    }

    private static void exercise(PrintStream stream) {
        stream.println("Scenario: Pend");
        stream.print("hello");
        stream.printf(" n=%d%n", 3);
        stream.println("CHAR".toCharArray());
        byte[] bytes = "byte\n".getBytes(StandardCharsets.UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }

    private static Logger consoleLogger(LoggerContext context) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();
        if (!encoder.isStarted()) {
            throw new AssertionError("encoder did not start: " + context.getStatusManager().getCopyOfStatusList());
        }
        ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        appender.setName("console");
        appender.setTarget("System.out");
        appender.setEncoder(encoder);
        appender.start();
        if (!appender.isStarted()) {
            throw new AssertionError("appender did not start: " + context.getStatusManager().getCopyOfStatusList());
        }
        context.start();
        Logger logger = context.getLogger("probe");
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        return logger;
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty("pkb_run_purpose");
        else System.setProperty("pkb_run_purpose", previous);
    }
}
