package tools.dscode.cucumberextended.dynamicparsing;

// MatchReplacement.java
public abstract class MatchReplacement {
    public final String key;
    MatchReplacement(String key){ this.key=key; }
    // Add one or more named groups to the alternation; each name must be unique in the Masker.
    abstract void contributeRegex(StringBuilder alt, java.util.List<GroupSpec> groups, int id);
    // Override to transform the captured text before storing/restoring.
    public String onCapture(String s){ return s; }

    // Small helper the subclasses can use
    static String unescaped(String lit){
        return "(?<!\\\\)(?:\\\\\\\\)*\\Q"+lit+"\\E";
    }

    // Internal: identifies which MR and which kind a group name represents
    static final class GroupSpec { final int mrIdx; final char kind; final String name;
        GroupSpec(int mrIdx, char kind, String name){ this.mrIdx=mrIdx; this.kind=kind; this.name=name; }
    }
}
