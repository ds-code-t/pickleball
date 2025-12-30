package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;


import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

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
        try {
            return new Result(block.run(), null);
        } catch (Throwable t) {
            return new Result(null, t);
        }
    }

    public static Result run(Runnable block) {
        try {
            block.run();
            return new Result(null, null);
        } catch (Throwable t) {
            return new Result(null, t);
        }
    }





}
