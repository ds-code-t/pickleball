package com.example.pickleball;

import com.example.pickleball.support.LocalTestSite;
import tools.dscode.common.annotations.LifecycleHook;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;

/**
 * Pickleball runner discovered by Maven Surefire because its name ends in "Tests".
 */
public final class PickleballTests extends PickleballRunner {
    private static final int TEST_SITE_PORT = 8765;
    private static LocalTestSite testSite;

    @Override
    public void globalTestDefaults() {
        PKB_props.glue("com.example.pickleball");
        PKB_props.features("classpath:features");
        PKB_props.plugins("pretty");
        PKB_props.tags("@all");
        PKB_props.browser("chrome");
    }

    @LifecycleHook(Phase.BEFORE_CUCUMBER_RUN)
    public static void beforeRun() {
        registerProjectElementVocabulary();
        // local site for testing.
        testSite = LocalTestSite.start(TEST_SITE_PORT);
    }

    private static void registerProjectElementVocabulary() {
        ExecutionDictionary dictionary = getExecutionDictionary();

        dictionary.category("Radio Button")
                .addBase("//input[@type='radio']");

        dictionary.category("Test Panel")
                .inheritsFrom(ExecutionDictionary.CONTAINS_TEXT)
                .addBase(
                        "//section[contains(concat(' ', normalize-space(@class), ' '), ' test-panel ')]"
                );

        dictionary.category("Product Card")
                .inheritsFrom(ExecutionDictionary.CONTAINS_TEXT)
                .addBase(
                        "//article[contains(concat(' ', normalize-space(@class), ' '), ' product-card ')]"
                );

        dictionary.category("Status Badge")
                .inheritsFrom(ExecutionDictionary.CONTAINS_TEXT)
                .addBase(
                        "//*[contains(concat(' ', normalize-space(@class), ' '), ' status-badge ')]"
                );
    }

    @LifecycleHook(Phase.AFTER_CUCUMBER_RUN)
    public static void stopLocalTestSite() {
        //closing local site for testing.
        if (testSite != null) {
            testSite.close();
            testSite = null;
        }
    }
}
