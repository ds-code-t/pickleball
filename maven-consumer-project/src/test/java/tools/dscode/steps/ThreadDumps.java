//package tools.dscode.steps;
//
//import java.io.PrintStream;
//import java.time.Duration;
//import java.util.Map;
//import java.util.Set;
//
//public final class ThreadDumps {
//
//    private ThreadDumps() {}
//
//    public static void dumpStacksAsync(String label) {
//        Thread t = new Thread(() -> {
//            try {
//                System.err.println("\n=== THREAD STACK DUMP (filtered) " + label + " ===");
//
//                var all = Thread.getAllStackTraces();
//
//                // main thread
//                for (var e : all.entrySet()) {
//                    Thread th = e.getKey();
//                    if ("main".equals(th.getName())) {
//                        printThread(System.err, th, e.getValue(), 80);
//                        break;
//                    }
//                }
//
//                // ForkJoinPool-2 workers
//                for (var e : all.entrySet()) {
//                    Thread th = e.getKey();
//                    if (th.getName().startsWith("ForkJoinPool-2-worker")) {
//                        printThread(System.err, th, e.getValue(), 60);
//                    }
//                }
//
//                System.err.println("=== END THREAD STACK DUMP ===\n");
//            } catch (Throwable ex) {
//                ex.printStackTrace(System.err);
//            } finally {
//                System.err.flush();
//            }
//        }, "thread-stackdump-offpool");
//
//        t.setDaemon(true);
//        t.start();
//    }
//
//    private static void printThread(PrintStream out, Thread t, StackTraceElement[] st, int max) {
//        out.println("THREAD: " + t.getName()
//                + " daemon=" + t.isDaemon()
//                + " state=" + t.getState());
//        int n = Math.min(st.length, max);
//        for (int i = 0; i < n; i++) out.println("  at " + st[i]);
//        if (st.length > n) out.println("  ... (" + (st.length - n) + " more)");
//        out.println();
//    }
//
//
//    public static void dumpAtEndAsync(String label) {
//        Thread offPool = new Thread(() -> {
//            try {
//                System.err.println();
//                System.err.println("=== THREAD DUMP (async) " + label + " ===");
//
//                // Keep this light: just names + state (avoid getAllStackTraces if you can)
//                for (Thread t : Thread.getAllStackTraces().keySet()) {
//                    if (t.isAlive() && (!t.isDaemon() || t.getName().startsWith("ForkJoinPool-"))) {
//                        System.err.println((t.isDaemon() ? "DAEMON: " : "NON-DAEMON: ")
//                                + t.getName() + " state=" + t.getState());
//                    }
//                }
//
//                System.err.println("=== END THREAD DUMP ===");
//                System.err.println();
//            } catch (Throwable t) {
//                t.printStackTrace(System.err);
//            } finally {
//                System.err.flush();
//            }
//        }, "thread-dump-offpool");
//
//        offPool.setDaemon(true); // crucial: don't keep JVM alive
//        offPool.start();
//    }
//
//
//    /**
//     * Safe-ish thread dump to call at the end of a run/hook.
//     * Runs on a dedicated thread (not the calling thread) to avoid blocking engine pools.
//     */
//    public static void dumpAtEnd(String label) {
//        dumpAtEnd(label, System.err, Duration.ofSeconds(2));
//    }
//
//    public static void dumpAtEnd(String label, PrintStream out, Duration joinTimeout) {
//        Runnable task = () -> {
//            try {
//                out.println();
//                out.println("=== THREAD DUMP @END: " + label + " ===");
//
//                Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
//
//                // 1) Always show main thread
//                dumpThreadByName(out, all, "main", 80);
//
//                // 2) Show ForkJoinPool workers (tune pool prefix if needed)
//                dumpThreadsByPrefix(out, all, "ForkJoinPool-", 40);
//
//                // 3) Show any NON-DAEMON threads (except main, already printed)
//                dumpNonDaemon(out, all, Set.of("main"), 25);
//
//                out.println("=== END THREAD DUMP ===");
//                out.println();
//            } catch (Throwable t) {
//                out.println("THREAD DUMP FAILED: " + t);
//                t.printStackTrace(out);
//            } finally {
//                out.flush();
//            }
//        };
//
//        Thread offPool = new Thread(task, "thread-dump-offpool");
//        offPool.setDaemon(false); // ensure it can run even if JVM is winding down
//        offPool.start();
//
//        try {
//            offPool.join(Math.max(1, joinTimeout.toMillis()));
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            // Best effort: don't throw from end hook
//        }
//    }
//
//    private static void dumpThreadByName(PrintStream out, Map<Thread, StackTraceElement[]> all,
//                                         String name, int maxFrames) {
//        for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
//            Thread t = e.getKey();
//            if (name.equals(t.getName())) {
//                dumpOne(out, t, e.getValue(), maxFrames);
//                return;
//            }
//        }
//        out.println("THREAD: " + name + " (not found)");
//        out.println();
//    }
//
//    private static void dumpThreadsByPrefix(PrintStream out, Map<Thread, StackTraceElement[]> all,
//                                            String prefix, int maxFrames) {
//        boolean any = false;
//        for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
//            Thread t = e.getKey();
//            if (t.getName().startsWith(prefix)) {
//                any = true;
//                dumpOne(out, t, e.getValue(), maxFrames);
//            }
//        }
//        if (!any) {
//            out.println("THREADS: prefix '" + prefix + "' (none found)");
//            out.println();
//        }
//    }
//
//    private static void dumpNonDaemon(PrintStream out, Map<Thread, StackTraceElement[]> all,
//                                      Set<String> excludeNames, int maxFrames) {
//        boolean any = false;
//        for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
//            Thread t = e.getKey();
//            if (t.isAlive() && !t.isDaemon() && !excludeNames.contains(t.getName())) {
//                any = true;
//                dumpOne(out, t, e.getValue(), maxFrames);
//            }
//        }
//        if (!any) {
//            out.println("NON-DAEMON: (none besides excluded)");
//            out.println();
//        }
//    }
//
//    private static void dumpOne(PrintStream out, Thread t, StackTraceElement[] st, int maxFrames) {
//        out.println("THREAD: " + t.getName()
//                + " daemon=" + t.isDaemon()
//                + " state=" + t.getState()
//                + " group=" + (t.getThreadGroup() == null ? "null" : t.getThreadGroup().getName()));
//        int n = Math.min(st.length, maxFrames);
//        for (int i = 0; i < n; i++) {
//            out.println("  at " + st[i]);
//        }
//        if (st.length > n) out.println("  ... (" + (st.length - n) + " more)");
//        out.println();
//    }
//}
