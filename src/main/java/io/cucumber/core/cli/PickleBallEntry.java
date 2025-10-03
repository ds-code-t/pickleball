package io.cucumber.core.cli;

import tools.ds.modkit.EnsureInstalled;


public class PickleBallEntry {
    static {
        EnsureInstalled.ensureOrDie();
    }
    public static void main(String[] args) {
        Main.main(args);
    }
}


