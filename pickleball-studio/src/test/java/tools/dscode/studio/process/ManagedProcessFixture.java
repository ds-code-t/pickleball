package tools.dscode.studio.process;

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
        System.out.println("fixture");
    }
}
