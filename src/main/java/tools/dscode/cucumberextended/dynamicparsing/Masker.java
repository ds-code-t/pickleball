package tools.dscode.cucumberextended.dynamicparsing;// Masker.java
import tools.dscode.cucumberextended.dynamicparsing.MatchReplacement;
import tools.dscode.cucumberextended.dynamicparsing.TextMasking;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

public final class Masker {
    // token fences (private-use)
    static final char K_L='\uF115', K_R='\uF116';

    final String key;
    final List<MatchReplacement> mrs;
    final Pattern pat;
    final List<MatchReplacement.GroupSpec> specs;

    public Masker(String key, MatchReplacement... defs){
        this.key=key; this.mrs=List.of(defs);
        StringBuilder alt=new StringBuilder(); List<MatchReplacement.GroupSpec> gs=new ArrayList<>();
        for(int i=0;i<defs.length;i++) defs[i].contributeRegex(alt, gs, i);
        this.pat = Pattern.compile(alt.length()==0? "(?!)" : alt.toString(), Pattern.DOTALL);
        this.specs = gs;
    }

    public String mask(String text, TextMasking tm){
        Matcher m = pat.matcher(text);
        StringBuilder out = new StringBuilder();
        int last=0, active=-1, start=0;
        while(m.find()){
            // identify which group fired
            MatchReplacement.GroupSpec hit=null;
            for (var s:specs) if (m.group(s.name)!=null) { hit=s; break; }
            if (hit==null) continue;

            if (active==-1){
                if (hit.kind=='G'){
                    String orig = m.group();
                    String token = tm.store(mrs.get(hit.mrIdx).key, key, mrs.get(hit.mrIdx).onCapture(orig));
                    out.append(text, last, m.start()).append(token);
                    last = m.end();
                }else if (hit.kind=='O' || hit.kind=='S'){
                    active = hit.mrIdx; start = m.start();
                } // ignore stray 'C' if seen without opener
            }else{
                if (hit.mrIdx==active && (hit.kind=='C' || hit.kind=='S')){
                    String orig = text.substring(start, m.end());
                    String token = tm.store(mrs.get(active).key, key, mrs.get(active).onCapture(orig));
                    out.append(text, last, start).append(token);
                    last = m.end(); active=-1;
                } // otherwise ignore events until we close the active MR
            }
        }
        return out.append(text, last, text.length()).toString();
    }

    public String unmask(String text, TextMasking tm){
        StringBuilder out=new StringBuilder();
        for(int i=0;i<text.length();){
            if (text.charAt(i)==K_L){
                int j=text.indexOf(K_R,i+1);
                if (j>i){
                    String tok=text.substring(i,j+1);
                    if (tok.endsWith(this.key+String.valueOf(K_R))){
                        String val=tm.lookup(tok);
                        if (val!=null){ out.append(val); i=j+1; continue; }
                    }
                }
            }
            out.append(text.charAt(i++));
        }
        return out.toString();
    }

    static char keyLeft(){ return K_L; }
    static char keyRight(){ return K_R; }
}
