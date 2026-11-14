package tools.dscode.common.composite;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EngineQueryFallbackTest {
    @Test
    void operatorsAndRelationsStayOnTheEngine() {
        assertNull(EngineQuery.of("css=a and text=b").seleniumFallback());
        assertNull(EngineQuery.of("role=x AND near(css=y)").seleniumFallback());
        assertNull(EngineQuery.of("not-near=div").seleniumFallback());
        assertNull(EngineQuery.of("NOT css=a").seleniumFallback());
        By css = EngineQuery.of("css=a").seleniumFallback();
        assertEquals(By.cssSelector("a").getClass(), css.getClass());
        assertEquals(By.xpath("//button").getClass(), EngineQuery.of("xpath=//button").seleniumFallback().getClass());
        assertEquals(By.xpath("//button").getClass(), EngineQuery.of("//button").seleniumFallback().getClass());
        assertEquals(By.cssSelector("button").getClass(), EngineQuery.of("button").seleniumFallback().getClass());
        assertEquals("near", EngineQuery.visualRelative("role=button AND not-near(text=\"Cancel\")"));
        assertEquals("inside", EngineQuery.visualRelative("not-inside=#dialog"));
    }
}
