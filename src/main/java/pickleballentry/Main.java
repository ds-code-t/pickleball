package pickleballentry;

import tools.ds.modkit.EnsureInstalled;


public class Main {
    static {
        EnsureInstalled.ensureOrDie();
    }
    public static void main(String[] args) {
        io.cucumber.core.cli.Main.main(args);
    }
}


