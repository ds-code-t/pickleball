package tools.dscode.studio.process;

import java.nio.file.Path;

public final class ManagedProcessFixture {
    private ManagedProcessFixture() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "output" : args[0];
        if ("delay".equals(mode)) {
            System.out.println("first");
            System.out.flush();
            Thread.sleep(300);
            System.err.println("second");
            System.err.flush();
            return;
        }
        if ("sleep".equals(mode)) {
            Thread.sleep(10_000);
            return;
        }
        if ("spawn".equals(mode)) {
            Process child = new ProcessBuilder(javaCommand("sleep"))
                    .inheritIO()
                    .start();
            System.out.println("child=" + child.pid());
            System.out.flush();
            child.waitFor();
            return;
        }
        System.out.println("fixture");
    }

    private static String[] javaCommand(String mode) {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return new String[]{
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ManagedProcessFixture.class.getName(),
                mode
        };
    }
}
