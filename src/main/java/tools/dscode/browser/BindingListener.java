package tools.dscode.browser;

/**
 * Push callback for a named browser binding.
 * CDP delivers {@code Runtime.bindingCalled}. BiDi delivers a channel message.
 * The listener runs on the adapter's reader thread.
 */
@FunctionalInterface
public interface BindingListener {
    void onEvent(String name, Object payload);
}
