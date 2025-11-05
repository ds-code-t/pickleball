package tools.dscode.cucumberextended.dynamicparsing;

// BookEndReplacement.java
public class BookEndReplacement extends MatchReplacement {
    private final String open, close;
    private final boolean same;
    public BookEndReplacement(String key, String open, String close){
        super(key);
        this.open=open;
        this.close=close;
        this.same=open.equals(close);
    }
    @Override
    void contributeRegex(StringBuilder alt, java.util.List<GroupSpec> groups, int id){
        if (alt.length()>0) alt.append("|");
        if (same){
            String g="S"+id;
            alt.append("(?<").append(g).append(">").append(unescaped(open)).append(")");
            groups.add(new GroupSpec(id,'S',g));
        } else {
            String go="O"+id, gc="C"+id;
            alt.append("(?<").append(go).append(">").append(unescaped(open)).append(")")
                    .append("|(?<").append(gc).append(">").append(unescaped(close)).append(")");
            groups.add(new GroupSpec(id,'O',go));
            groups.add(new GroupSpec(id,'C',gc));
        }
    }
}
