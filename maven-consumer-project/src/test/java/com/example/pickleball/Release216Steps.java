package com.example.pickleball;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.java.en.Given;
import org.openqa.selenium.By;
import tools.dscode.common.reporting.logging.Entry;
import com.example.pickleball.support.InternalJavaTestRunner;
import tools.dscode.coredefinitions.BrowserSteps;
import tools.dscode.coredefinitions.ObjectRegistrationSteps;

import tools.dscode.PrefixValue;

import static tools.dscode.Values.getScenarioValue;
import static tools.dscode.Values.getValue;
import static tools.dscode.Values.getStepValue;
import static tools.dscode.Values.registerPrefix;
import static tools.dscode.Values.saveScenarioValue;
import static tools.dscode.Values.saveStepValue;
import static tools.dscode.Values.saveValue;
import static tools.dscode.Values.secret;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

/**
 * Glue for the 2.1.16 consumer scenarios.
 * The password prefix is registered once, when Cucumber loads this class.
 */
public final class Release216Steps {
    public static final String ACCOUNT = "pkb-secret-216-consumer";
    public static final String DB_PASSWORD = "pkb-secret-216-dbpass";

    private static final ThreadLocal<String> EXPECTED_CLEANUP = new ThreadLocal<>();
    private static final ThreadLocal<String> CLEANED = new ThreadLocal<>();

    static {
        registerPrefix("password", (ctx, arg) -> switch (arg) {
            case "testAccount" -> secret(ACCOUNT);
            case "qaDb" -> secret(DB_PASSWORD);
            default -> throw new IllegalArgumentException(
                    "Unknown password account."
            );
        });
    }

    @Given("^RUN RELEASE 216 JAVA TESTS$")
    public static void runJavaTests() {
        InternalJavaTestRunner.run(Release216Checks.class).stream()
                .filter(result -> !result.passed())
                .findFirst()
                .ifPresent(result -> {
                    throw new AssertionError(result.display(), result.failure());
                });
    }

    @Given("^RUN RELEASE 216 LANE (one|two)$")
    public static void lane(String lane) throws InterruptedException {
        saveScenarioValue("laneScenario", lane);
        saveStepValue("laneStep", lane);
        Thread.sleep(250);
        if (!lane.equals(String.valueOf(getScenarioValue("laneScenario")))) {
            throw new AssertionError("Scenario values were not isolated.");
        }
        if (!lane.equals(String.valueOf(getStepValue("laneStep")))) {
            throw new AssertionError("Step values were not isolated.");
        }
    }

    @Given("^RUN RELEASE 216 CLICKED (.+)$")
    public static void clicked(String id) {
        String actual = BrowserSteps.getCurrentDriver()
                .findElement(By.id("clicked"))
                .getText();
        if (!id.equals(actual)) {
            throw new AssertionError("Clicked button was " + actual + ".");
        }
    }

    @Given("^RUN RELEASE 216 SECRET FIELD$")
    public static void secretField() {
        String actual = BrowserSteps.getCurrentDriver()
                .findElement(By.id("secret"))
                .getAttribute("value");
        if (!("Bearer " + ACCOUNT).equals(actual)) {
            throw new AssertionError("The textbox did not receive the prefix value.");
        }
        assertLogHidesSecrets();
    }

    @Given("^RUN RELEASE 216 UNMASK$")
    public static void unmaskSavedPrefix() {
        if (!ACCOUNT.equals(String.valueOf(getValue("pwPlain")))) {
            throw new AssertionError("UNMASK did not return the prefix value.");
        }
        if (!PrefixValue.MASK.equals(String.valueOf(getValue("pw")))) {
            throw new AssertionError("A masked value did not stay masked.");
        }
        assertLogHidesSecrets();
    }

    @Given("^RUN RELEASE 216 LOG HIDES SECRETS$")
    public static void logHidesSecrets() {
        assertLogHidesSecrets();
    }

    @Given("^RUN RELEASE 216 PREPARE JDBC$")
    public static void prepareJdbc() {
        saveValue(
                "jdbcUrl",
                "jdbc:h2:mem:release216c" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
        );
    }

    @Given("^RUN RELEASE 216 REGISTER CLEANUP (.+)$")
    public static void registerCleanup(String token) {
        ObjectNode config = MAPPER.createObjectNode();
        String pathKey = "release216.marker." + token;
        config.put(ObjectRegistrationSteps.pathKeyProperty, pathKey);
        config.put("constructor", "RELEASE_216_MARKER");
        config.put(
                ObjectRegistrationSteps.cleanupProperty,
                "MARK_RELEASE_216: " + token
        );
        ObjectRegistrationSteps.registerObjectAndConfig(pathKey, new Marker(token), config);
        EXPECTED_CLEANUP.set(token);
    }

    @Given(ObjectRegistrationSteps.objAction + "MARK_RELEASE_216: (.+)$")
    public static void markRelease216(String token, Object registered) {
        if (!(registered instanceof Marker marker) || !token.equals(marker.token)) {
            throw new AssertionError("Registered-object cleanup did not receive the scenario object.");
        }
        CLEANED.set(token.trim());
    }

    public static void afterScenarioCleanup() {
        String expected = EXPECTED_CLEANUP.get();
        try {
            if (expected != null) {
                String actual = CLEANED.get();
                if (!expected.equals(actual)) {
                    throw new AssertionError(
                            "Registered-object cleanup did not run for the scenario."
                    );
                }
            }
        } finally {
            EXPECTED_CLEANUP.remove();
            CLEANED.remove();
        }
    }

    private static void assertLogHidesSecrets() {
        Entry root = CurrentScenarioState.getScenarioLogRoot();
        StringBuilder text = new StringBuilder();
        appendLog(text, root);
        String log = text.toString();
        if (log.contains(ACCOUNT) || log.contains(DB_PASSWORD)) {
            throw new AssertionError("A scenario log contains a plain secret.");
        }
    }

    private static void appendLog(StringBuilder text, Entry entry) {
        if (entry == null) {
            return;
        }
        if (entry.text != null) {
            text.append(entry.text).append('\n');
        }
        for (Entry child : entry.children) {
            appendLog(text, child);
        }
    }

    private static final class Marker {
        private final String token;

        private Marker(String token) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("Cleanup token is required.");
            }
            this.token = token;
        }

        @Override
        public String toString() {
            return "release-216-marker";
        }
    }
}
