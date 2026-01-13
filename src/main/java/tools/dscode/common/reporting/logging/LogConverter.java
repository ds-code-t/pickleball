// file: tools/dscode/common/reporting/logging/LogConverter.java
package tools.dscode.common.reporting.logging;

public interface LogConverter {

    default void onEvent(ConvertEvent event, Log log, Entry entry) { }

    default void close() { }
}
