// file: tools/dscode/common/reporting/logging/EntryListener.java
package tools.dscode.common.reporting.logging;

public interface EntryListener {
    default void onCreated(Entry scope, Entry entry) { }
    default void onUpdated(Entry scope, Entry entry) { }
    default void onClosed(Entry scope, Entry entry)  { }
}
