// package tools.dscode.runtime;
//
// import java.util.Arrays;
// import java.util.Map;
// import java.util.Objects;
// import java.util.concurrent.ConcurrentHashMap;
//
///**
// * Tiny, reusable instance registry supporting GLOBAL and THREAD-LOCAL scopes.
// * Keys may be objects; they are normalized to Strings via Arrays.deepToString
// * of the key array.
// */
// public final class InstanceRegistry {
// private InstanceRegistry() {
// }
//
// private static final Map<String, Object> GLOBAL = new ConcurrentHashMap<>();
// private static final ThreadLocal<Map<String, Object>> TL =
// ThreadLocal.withInitial(ConcurrentHashMap::new);
//
// private static String norm(Object... keys) {
// return
// Arrays.deepToString(Arrays.stream(keys).filter(Objects::nonNull).toArray());
// }
//
// // ---- thread-local ----
// public static void register(Object instance, Object... keys) {
// TL.get().put(norm(keys), instance);
// }
//
// public static Object get(Object... keys) {
// return TL.get().get(norm(keys));
// }
//
// public static void clearThread() {
// TL.remove();
// }
//
// // ---- global ----
// public static void globalRegister(Object instance, Object... keys) {
// GLOBAL.put(norm(keys), instance);
// }
//
// public static Object globalGet(Object... keys) {
// return GLOBAL.get(norm(keys));
// }
//
// public static void clearGlobal() {
// GLOBAL.clear();
// }
// }
