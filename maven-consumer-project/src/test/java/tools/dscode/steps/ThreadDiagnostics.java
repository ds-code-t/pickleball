//package tools.dscode.steps;
//
//import java.lang.management.ManagementFactory;
//import java.lang.management.ThreadInfo;
//import java.lang.management.ThreadMXBean;
//import java.time.Instant;
//import java.util.Arrays;
//import java.util.concurrent.ForkJoinPool;
//
//public final class ThreadDiagnostics {
//
//    private ThreadDiagnostics() {}
//
//    public static void dumpAllThreads(String label) {
//        System.err.println("\n=== THREAD DUMP BEGIN: " + label + " @ " + Instant.now() + " ===");
//
//        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
//        long[] ids = mx.getAllThreadIds();
//        ThreadInfo[] infos = mx.getThreadInfo(ids, Integer.MAX_VALUE);
//
//        for (ThreadInfo ti : infos) {
//            if (ti == null) continue;
//            System.err.println((ti.isDaemon() ? "DAEMON" : "NON-DAEMON") +
//                    ": " + ti.getThreadName() +
//                    " state=" + ti.getThreadState() +
//                    " id=" + ti.getThreadId());
//
//            if (ti.getLockName() != null) {
//                System.err.println("  waitingOn=" + ti.getLockName() +
//                        (ti.getLockOwnerName() != null ? " owner=" + ti.getLockOwnerName() : ""));
//            }
//
//            for (StackTraceElement ste : ti.getStackTrace()) {
//                System.err.println("  at " + ste);
//            }
//            System.err.println();
//        }
//
//        dumpForkJoinPools();
//        System.err.println("=== THREAD DUMP END: " + label + " ===\n");
//        System.err.flush();
//    }
//
//    /** Best-effort: prints stats for common pool + any known custom pool. */
//    public static void dumpForkJoinPools() {
//        System.err.println("=== ForkJoinPool diagnostics ===");
//        ForkJoinPool common = ForkJoinPool.commonPool();
//        dumpPool("commonPool", common);
//
//        // If JUnit creates its own pool, we can't directly reference it,
//        // but we can still infer from thread names in the full dump.
//        System.err.println("=== end ForkJoinPool diagnostics ===");
//    }
//
//    private static void dumpPool(String name, ForkJoinPool pool) {
//        System.err.println(name + ": parallelism=" + pool.getParallelism() +
//                " size=" + pool.getPoolSize() +
//                " active=" + pool.getActiveThreadCount() +
//                " running=" + pool.getRunningThreadCount() +
//                " queuedTasks=" + pool.getQueuedTaskCount() +
//                " queuedSubmissions=" + pool.getQueuedSubmissionCount() +
//                " steals=" + pool.getStealCount() +
//                " isShutdown=" + pool.isShutdown() +
//                " isTerminated=" + pool.isTerminated() +
//                " isQuiescent=" + pool.isQuiescent());
//    }
//}
