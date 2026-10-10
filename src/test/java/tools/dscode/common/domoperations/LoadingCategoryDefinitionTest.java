package tools.dscode.common.domoperations;

import com.xpathy.XPathy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.dscode.common.domoperations.ExecutionDictionary.CategoryFlags;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;

class LoadingCategoryDefinitionTest {

    @AfterEach
    void restoreLoading() {
        getExecutionDictionary().restoreBuiltinLoading();
    }

    @Test
    void builtinXpathIsTightAndLoadingsInheritsTheFlag() {
        String xpath = xpath("Loading");
        assertTrue(xpath.contains("@aria-busy='true'"), xpath);
        assertTrue(xpath.contains("@role='progressbar'"), xpath);
        assertTrue(xpath.contains("@data-loading='true'"), xpath);
        assertTrue(xpath.contains("@data-state='loading'"), xpath);
        assertTrue(xpath.contains("self::progress"), xpath);
        assertTrue(xpath.contains("not(@value)"), xpath);
        assertTrue(xpath.contains("number(@value) < 1"), xpath);
        assertTrue(xpath.contains("number(@value) < number(@max)"), xpath);
        assertTrue(xpath.contains("self::meter and @aria-busy='true'"), xpath);
        assertTrue(xpath.contains("@role='status' and @aria-busy='true'"), xpath);
        assertTrue(xpath.contains("' loading '"), xpath);
        assertTrue(xpath.contains("' spinner '"), xpath);
        assertTrue(xpath.contains("normalize-space(@data-testid)"), xpath);
        String builtin = ExecutionDictionary.BUILTIN_LOADING_XPATH;
        assertFalse(builtin.contains("@class"), builtin);
        assertFalse(builtin.contains("contains(@data-testid"), builtin);
        assertFalse(xpath.contains("contains(@data-testid"), xpath);
        assertFalse(xpath.contains("or self::meter"), xpath);
        assertFalse(xpath.contains("or @role='status'"), xpath);
        assertFalse(xpath.contains("or @role = 'status'"), xpath);
        assertTrue(flags("Loading").contains(CategoryFlags.ALL_CONTEXTS));
        assertTrue(flags("Loadings").contains(CategoryFlags.ALL_CONTEXTS));
        assertTrue(xpath("Loadings").contains("@aria-busy='true'"));
        assertFalse(flags("Button").contains(CategoryFlags.ALL_CONTEXTS));
    }

    @Test
    void aCategoryThatExtendsLoadingInheritsTheFlagAndLocator() {
        ExecutionDictionary dictionary = getExecutionDictionary();
        try {
            dictionary.category("Busy Overlay").inheritsFrom("Loading");
            assertTrue(flags("Busy Overlay").contains(CategoryFlags.ALL_CONTEXTS));
            assertTrue(xpath("Busy Overlay").contains("@aria-busy='true'"));
            assertFalse(flags("Button").contains(CategoryFlags.ALL_CONTEXTS));
        } finally {
            dictionary.resetCategory("Busy Overlay");
        }
    }

    @Test
    void orAddsAClauseAndKeepsTheDefaultAndTheFlag() {
        ExecutionDictionary dictionary = getExecutionDictionary();
        dictionary.category("Loading").or((category, value, op) -> XPathy.from("//*[@data-extra-loading='1']"));
        String xpath = xpath("Loading");
        assertTrue(xpath.contains("@aria-busy='true'"), xpath);
        assertTrue(xpath.contains("data-extra-loading"), xpath);
        assertTrue(flags("Loading").contains(CategoryFlags.ALL_CONTEXTS));
        assertTrue(flags("Loadings").contains(CategoryFlags.ALL_CONTEXTS));
    }

    @Test
    void resetClearsTheFlagAndTheChildLinkKeepsInheritingNothingUntilRestore() {
        ExecutionDictionary dictionary = getExecutionDictionary();
        dictionary.category("Loading").reset();
        assertFalse(flags("Loading").contains(CategoryFlags.ALL_CONTEXTS));
        assertFalse(flags("Loadings").contains(CategoryFlags.ALL_CONTEXTS));
        XPathy cleared = dictionary.getCategoryXPathy("Loading");
        assertTrue(cleared == null || !cleared.getXpath().contains("@aria-busy"), String.valueOf(cleared));

        dictionary.category("Loading").flags(CategoryFlags.ALL_CONTEXTS);
        assertTrue(flags("Loadings").contains(CategoryFlags.ALL_CONTEXTS));

        dictionary.restoreBuiltinLoading();
        assertTrue(xpath("Loading").contains("@aria-busy='true'"));
        assertTrue(xpath("Loading").contains("normalize-space(@data-testid)"));
        assertFalse(xpath("Loading").contains("data-extra-loading"));
        assertTrue(flags("Loading").contains(CategoryFlags.ALL_CONTEXTS));
        assertTrue(flags("Loadings").contains(CategoryFlags.ALL_CONTEXTS));
        assertNotNull(dictionary.getCategoryXPathy("Loadings"));
    }

    private static String xpath(String category) {
        XPathy xpath = getExecutionDictionary().getCategoryXPathy(category);
        assertNotNull(xpath, category);
        return xpath.getXpath();
    }

    private static java.util.Set<CategoryFlags> flags(String category) {
        return getExecutionDictionary().getResolvedCategoryFlags(category);
    }
}
