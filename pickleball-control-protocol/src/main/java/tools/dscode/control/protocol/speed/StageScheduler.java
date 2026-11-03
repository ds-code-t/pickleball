package tools.dscode.control.protocol.speed;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs independent Workbench stages together. A failure is recorded and the
 * caller falls back to the sequential path. Overlap is a real interval, not a flag.
 */
public final class StageScheduler {
    private StageScheduler() {
    }

    public record Task(String name, Callable<String> body) {
    }

    public record Mark(String name, long startNs, long endNs, boolean ok, String detail) {
        public long elapsedMs() {
            return Math.max(0L, (endNs - startNs) / 1_000_000L);
        }
    }

    public record Outcome(List<Mark> marks, List<String> overlaps, boolean failed) {
        public Outcome {
            marks = List.copyOf(marks == null ? List.of() : marks);
            overlaps = List.copyOf(overlaps == null ? List.of() : overlaps);
        }

        public String overlapDetail() {
            return String.join(",", overlaps);
        }
    }

    /** {@code parallel=false} is the sequential fallback. */
    public static Outcome run(List<Task> tasks, boolean parallel) {
        List<Task> work = tasks == null ? List.of() : tasks;
        if (!parallel || work.size() < 2) return runSequential(work);
        ExecutorService pool = Executors.newFixedThreadPool(work.size(), runnable -> {
            Thread thread = new Thread(runnable, "pickleball-workbench-stage");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Mark>> futures = new ArrayList<>();
            for (Task task : work) futures.add(pool.submit(() -> call(task)));
            List<Mark> marks = new ArrayList<>();
            boolean failed = false;
            for (Future<Mark> future : futures) {
                Mark mark = future.get();
                marks.add(mark);
                if (!mark.ok()) failed = true;
            }
            return new Outcome(marks, overlaps(marks), failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(work, "interrupted");
        } catch (ExecutionException failure) {
            return failed(work, failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Outcome runSequential(List<Task> work) {
        List<Mark> marks = new ArrayList<>();
        boolean failed = false;
        for (Task task : work) {
            Mark mark = call(task);
            marks.add(mark);
            if (!mark.ok()) {
                failed = true;
                break;
            }
        }
        return new Outcome(marks, List.of(), failed);
    }

    private static Mark call(Task task) {
        long start = System.nanoTime();
        try {
            String detail = task.body() == null ? "" : task.body().call();
            long end = System.nanoTime();
            return new Mark(task.name(), start, end, true, detail == null ? "" : detail);
        } catch (Exception failure) {
            long end = System.nanoTime();
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new Mark(task.name(), start, end, false, message);
        }
    }

    static List<String> overlaps(List<Mark> marks) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < marks.size(); i++) {
            for (int j = i + 1; j < marks.size(); j++) {
                Mark left = marks.get(i);
                Mark right = marks.get(j);
                if (left.startNs() < right.endNs() && right.startNs() < left.endNs()) {
                    found.add(left.name() + "+" + right.name());
                }
            }
        }
        return found;
    }

    private static Outcome failed(List<Task> work, String detail) {
        List<Mark> marks = new ArrayList<>();
        for (Task task : work) marks.add(new Mark(task.name(), 0, 0, false, detail));
        return new Outcome(marks, List.of(), true);
    }
}
