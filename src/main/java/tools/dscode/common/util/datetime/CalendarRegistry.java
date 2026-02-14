package tools.dscode.common.util.datetime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.core.runner.StepExtension;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.mappings.GlobalMappings.rootDirectory;

public final class CalendarRegistry {
    private static final ObjectMapper OM = new ObjectMapper();
    private static final ConcurrentHashMap<String, BusinessCalendar> REGISTRY = new ConcurrentHashMap<>();

    // ---- one-time init gate ----
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean INITIALIZED = false;

    public static final ThreadLocal<BusinessCalendar> calendar =
            new ThreadLocal<>();

    public static final String DEFAULT_CALENDAR_NAME = "DefaultCalendar";
    public static final BusinessCalendar DEFAULT_CALENDAR = get(DEFAULT_CALENDAR_NAME);

    public static BusinessCalendar getCalendar() {
        BusinessCalendar c = calendar.get();
        return (c != null) ? c : DEFAULT_CALENDAR;
    }

    private CalendarRegistry() {}





    /**
     * Your custom logic hook: populate the registry once.
     * Keep this code entirely inside the init gate so all threads block until it’s done.
     */
    private static void initOnce() {
        System.out.println("Initializing calendar registry...");
        StepExtension currentStep = getRunningStep();
        String json = (String) currentStep.getStepParsingMap().getAndResolve(rootDirectory+ ".CALENDARS");
        registerJson(json);
    }

    private static void ensureInitialized() {
        if (INITIALIZED) return;                // fast path (volatile read)
        synchronized (INIT_LOCK) {
            if (INITIALIZED) return;            // double-check
            initOnce();                         // <-- your one-time registry population
            INITIALIZED = true;                 // publish
        }
    }

    public static void registerJson(String json) {
        try {
            JsonNode root = OM.readTree(json);
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                REGISTRY.put(e.getKey(), BusinessCalendar.fromJson(e.getValue()));
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid calendar JSON", ex);
        }
    }

    public static BusinessCalendar get(String name) {
        ensureInitialized(); // blocks everyone until first init completes
        return REGISTRY.get(name);
    }
}
