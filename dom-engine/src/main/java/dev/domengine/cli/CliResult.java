package dev.domengine.cli;

public record CliResult(int exit, String stdout, String stderr) {
    public static CliResult ok(String stdout) {
        return new CliResult(0, stdout == null ? "" : stdout, "");
    }

    public static CliResult fail(int exit, String stderr) {
        return new CliResult(exit, "", stderr == null ? "" : stderr);
    }
}
