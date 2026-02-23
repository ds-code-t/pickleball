// file: tools/dscode/common/reporting/logging/Log.java
package tools.dscode.common.reporting.logging;

import tools.dscode.common.reporting.WorkBook;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.cucumber.core.runner.GlobalState.getReport;
import static io.cucumber.core.runner.GlobalState.workBookMap;
import static tools.dscode.common.reporting.WorkBookConsolePrinter.printToConsole;


public final class Log {

    private static final Log GLOBAL = new Log();
    private final Set<BaseConverter> converters = ConcurrentHashMap.newKeySet();

    private Log() {
    }

    public static Log global() {
        return GLOBAL;
    }

    void register(BaseConverter converter) {
        converters.add(converter);
    }

    /**
     * Closes any converters that are still open (idempotent).
     */
    public void closeAll() {
        for (BaseConverter c : converters) c.close();
        printToConsole(getReport());
        for (WorkBook report : workBookMap.values()) {
            try {
                report.write();
            } catch (Exception e) {
                System.out.println("Report '" + report.outputFile + "' failed to write due to: " + e.getMessage());
            }
        }
    }

}
