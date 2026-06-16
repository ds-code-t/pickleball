package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;


import tools.dscode.common.exceptions.SoftRuntimeException;

public final class Attempt {

    private Attempt() {
    }

    public record Result(Object value, Throwable error) {
        public boolean ok() {
            return error == null;
        }

        public boolean failed() {
            return error != null;
        }

        public RuntimeException getRuntimeError(boolean softError) {
            if (softError)
                return new SoftRuntimeException(error);
            if (error instanceof RuntimeException)
                return (RuntimeException) error;
            return new RuntimeException(error);
        }
    }

    @FunctionalInterface
    public interface Block {
        Object run() throws Throwable;
    }

    public static Result run(Block block) {
        return run(1, 0, block);
    }

    public static Result run(int repetitions, Block block) {
        return run(repetitions, 0, block);
    }

    public static Result run(int repetitions, long waitMillis, Block block) {
        Result result = validateRepeatArguments(repetitions, waitMillis);
        if (result.failed())
            return result;

        for (int repetition = 1; repetition <= repetitions; repetition++) {
            result = waitBeforeRepeat(repetition, waitMillis);
            if (result.failed())
                return result;

            try {
                result = new Result(block.run(), null);
            } catch (Throwable t) {
                return new Result(null, t);
            }
        }

        return result;
    }

    public static Result runVoid(Runnable block) {
        return runVoid(1, 0, block);
    }

    public static Result runVoid(int repetitions, Runnable block) {
        return runVoid(repetitions, 0, block);
    }

    public static Result runVoid(int repetitions, long waitMillis, Runnable block) {
        return run(repetitions, waitMillis, () -> {
            block.run();
            return null;
        });
    }

    private static Result validateRepeatArguments(int repetitions, long waitMillis) {
        if (repetitions < 1)
            return new Result(null, new IllegalArgumentException("repetitions must be at least 1"));
        if (waitMillis < 0)
            return new Result(null, new IllegalArgumentException("waitMillis must be at least 0"));
        return new Result(null, null);
    }

    private static Result waitBeforeRepeat(int repetition, long waitMillis) {
        if (repetition == 1 || waitMillis == 0)
            return new Result(null, null);

        try {
            Thread.sleep(waitMillis);
            return new Result(null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, e);
        }
    }





}
