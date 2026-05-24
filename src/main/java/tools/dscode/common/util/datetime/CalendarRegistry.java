package tools.dscode.common.util.datetime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.mappings.ValueFormatting;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.mappings.ParsingMap.configsRoot;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;

public final class CalendarRegistry {

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
        logTrace("Initializing calendar registry...");
        StepExtension currentStep = getRunningStep();
//        String json = (String) currentStep.getStepParsingMap().getAndResolve(configsRoot + ".CALENDARS");
        Object raw = currentStep.getStepParsingMap().getAndResolve(configsRoot + ".CALENDARS");
        String json = null;
        try {
            json = (raw instanceof String) ? (String) raw : ValueFormatting.MAPPER.writeValueAsString(raw);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
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
            JsonNode root = MAPPER.readTree(json);
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
