package tools.dscode.engine.runtime;

import java.util.regex.Pattern;

/**
 * One parser for flag phrases, retry, and inline {@code until}.
 * Ingest writes the columns. The glue methods call {@link #applyFromPhrase}
 * so a native run and a Cucumber run share this writer.
 */
public final class StepFlags {
    private static final Pattern BARE_UNTIL = Pattern.compile("(?i)\\buntil\\b");
    private static volatile Applier applier = parsed -> {
    };

    private StepFlags() {
    }

    /** Live step writer. The Cucumber adapter installs the implementation. */
    public interface Applier {
        void apply(Parsed parsed);
    }

    public record Parsed(
            boolean retry,
            String remainder,
            boolean ignoreOnNext,
            boolean logOnNext,
            boolean ignoreFailures,
            boolean logFailuresContinue,
            boolean runIfFinished,
            boolean alwaysRun,
            boolean runIfPassing,
            boolean runIfFailed,
            boolean runIfHardFailed,
            boolean runIfSoftFailed,
            String untilMode,
            Integer maxIterations,
            Integer timeoutSeconds
    ) {
        public Parsed {
            remainder = remainder == null ? "" : remainder;
        }

        public Parsed withCeilings(Integer iterations, Integer seconds, String mode) {
            return new Parsed(
                    retry, remainder, ignoreOnNext, logOnNext, ignoreFailures, logFailuresContinue,
                    runIfFinished, alwaysRun, runIfPassing, runIfFailed, runIfHardFailed, runIfSoftFailed,
                    mode != null ? mode : untilMode,
                    iterations != null ? iterations : maxIterations,
                    seconds != null ? seconds : timeoutSeconds);
        }

        public Parsed overlayFlags(boolean ignore, boolean logContinue, boolean finished, boolean always,
                                    boolean passing, boolean failed, boolean hard, boolean soft, boolean doRetry) {
            return new Parsed(
                    retry || doRetry,
                    remainder,
                    ignoreOnNext,
                    logOnNext,
                    ignoreFailures || ignore,
                    logFailuresContinue || logContinue,
                    runIfFinished || finished,
                    alwaysRun || always,
                    runIfPassing || passing,
                    runIfFailed || failed,
                    runIfHardFailed || hard,
                    runIfSoftFailed || soft,
                    untilMode,
                    maxIterations,
                    timeoutSeconds);
        }
    }

    public static void install(Applier writer) {
        applier = writer == null ? parsed -> {
        } : writer;
    }

    public static void applyFromPhrase(String phrase) {
        applier.apply(parse(phrase));
    }

    public static Parsed parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        boolean retry = false;
        String remainder = "";
        if (text.startsWith("RETRY:")) {
            retry = true;
            remainder = text.substring("RETRY:".length()).trim();
        }
        boolean ignoreOnNext = "IGNORE FAILURES".equals(text);
        boolean logOnNext = "LOG FAILURES BUT CONTINUE SCENARIO".equals(text);
        boolean ignore = false;
        boolean log = false;
        boolean finished = false;
        String bare = text;
        if (bare.endsWith(" AND IGNORE FAILURES")) {
            ignore = true;
            bare = bare.substring(0, bare.length() - " AND IGNORE FAILURES".length()).trim();
        }
        if (bare.endsWith(" AND SCENARIO FINISHED")) {
            finished = true;
            bare = bare.substring(0, bare.length() - " AND SCENARIO FINISHED".length()).trim();
        }
        int colon = bare.indexOf(':');
        String head = colon >= 0 ? bare.substring(0, colon).trim() : bare;
        boolean always = false;
        boolean passing = false;
        boolean failed = false;
        boolean hard = false;
        boolean soft = false;
        switch (head) {
            case "ALWAYS RUN" -> always = true;
            case "RUN IF SCENARIO PASSING" -> passing = true;
            case "RUN IF SCENARIO FAILED" -> failed = true;
            case "RUN IF SCENARIO HARD FAILED" -> hard = true;
            case "RUN IF SCENARIO SOFT FAILED" -> soft = true;
            case "RUN IF SCENARIO FINISHED" -> finished = true;
            default -> {
            }
        }
        String untilMode = null;
        if (containsBareUntil(text)) {
            char end = text.charAt(text.length() - 1);
            untilMode = (end == ':' || end == '?') ? "block" : "inline";
        }
        return new Parsed(
                retry, remainder, ignoreOnNext, logOnNext, ignore, log, finished,
                always, passing, failed, hard, soft, untilMode, null, null);
    }

    static boolean containsBareUntil(String text) {
        if (text == null || text.isEmpty()) return false;
        StringBuilder bare = new StringBuilder(text.length());
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == quote) quoted = false;
                else bare.append(' ');
                continue;
            }
            if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
                bare.append(' ');
                continue;
            }
            bare.append(c);
        }
        return BARE_UNTIL.matcher(bare).find();
    }
}
