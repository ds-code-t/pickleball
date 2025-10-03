package pickleballentrymain;

import tools.ds.modkit.EnsureInstalled;


public class Main {
    static {
        EnsureInstalled.ensureOrDie();
    }

    public static void main(String[] args) {
        System.out.println("@@Main-pe1");
        io.cucumber.core.cli.Main.main(args);
    }
}


