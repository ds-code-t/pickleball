// file: tools/dscode/common/reporting/logging/Converters.java
package tools.dscode.common.reporting.logging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Converters {

    private static final List<LogConverter> LIST = new CopyOnWriteArrayList<>();

    private Converters() { }

    public static void register(LogConverter converter) {
        LIST.add(converter);
    }

    public static void clear() {
        LIST.clear();
    }

    static void emit(ConvertEvent event, Log log, Entry entry) {
        for (LogConverter c : LIST) c.onEvent(event, log, entry);
    }

    static void closeAll() {
        for (LogConverter c : LIST) c.close();
    }
}
