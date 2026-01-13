// file: tools/dscode/common/reporting/logging/Log.java
package tools.dscode.common.reporting.logging;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Log {

    private static final Log GLOBAL = new Log();
    private final Set<BaseConverter> converters = ConcurrentHashMap.newKeySet();

    private Log() { }

    public static Log global() {
        return GLOBAL;
    }

    void register(BaseConverter converter) {
        converters.add(converter);
    }

    /** Closes any converters that are still open (idempotent). */
    public void closeAll() {
        for (BaseConverter c : converters) c.close();
    }
}
