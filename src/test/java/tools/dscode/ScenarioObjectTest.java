package tools.dscode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScenarioObjectTest {
    @AfterEach
    void clear() {
        Values.clearScenarioObjects();
    }

    @Test
    void putAndGetKeepTheSameInstanceIncludingAWindowsPathName() {
        Object page = new Object();
        String windows = "C:\\Users\\Dan She'll\\page";
        Values.saveScenarioObject(windows, page);
        Values.saveScenarioObject("C:/Program Files/Pickleball/body.xml", "saved");
        assertSame(page, Values.getScenarioObject(windows));
        assertEquals("saved", Values.getScenarioObject("C:/Program Files/Pickleball/body.xml"));
        Values.saveScenarioObject(windows, null);
        assertNull(Values.getScenarioObject(windows));
        Values.clearScenarioObjects();
        assertNull(Values.getScenarioObject(windows));
    }

    @Test
    void anotherThreadDoesNotSeeTheObjectAndABlankNameIsRejected() throws Exception {
        Values.saveScenarioObject("token", "main");
        AtomicReference<Object> seen = new AtomicReference<>("unset");
        Thread other = new Thread(() -> seen.set(Values.getScenarioObject("token")));
        other.start();
        other.join();
        assertNull(seen.get());
        assertThrows(IllegalArgumentException.class, () -> Values.saveScenarioObject("  ", "x"));
        assertThrows(IllegalArgumentException.class, () -> Values.getScenarioObject(null));
    }
}
