package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

public final class Attempt {

    private Attempt() {}

    public record Result(Object value, Throwable error) {
        public boolean ok() { return error == null; }
        public boolean failed() { return error != null; }
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
