package tools.dscode.cucumberextended.dynamicparsing;

// MatchGroup.java
public class MatchGroup extends MatchReplacement {
    private final String pattern;
    public MatchGroup(String key, String pattern){
        super(key);
        this.pattern=pattern;

    @Override
    void contributeRegex(StringBuilder alt, java.util.List<GroupSpec> groups, int id){
        if (alt.length()>0) alt.append("|");
        String g="G"+id;
        alt.append("(?<").append(g).append(">").append(pattern).append(")");
        groups.add(new GroupSpec(id,'G',g));
    }
}
