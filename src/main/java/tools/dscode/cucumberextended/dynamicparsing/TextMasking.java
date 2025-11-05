package tools.dscode.cucumberextended.dynamicparsing;// TextMasking.java
import tools.dscode.cucumberextended.dynamicparsing.Masker;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class TextMasking {
    private final Map<String,String> map=new LinkedHashMap<>();
    private final AtomicInteger seq=new AtomicInteger();
    private java.util.List<Masker> maskOrder=List.of(), unmaskOrder=List.of();

    public TextMasking orderMask(Masker... ms){ maskOrder=List.of(ms); return this; }
    public TextMasking orderUnmask(Masker... ms){ unmaskOrder=List.of(ms); return this; }

    String store(String mrKey,String maskerKey,String val){
        String tok=""+Masker.keyLeft()+mrKey+seq.incrementAndGet()+maskerKey+Masker.keyRight();
        map.put(tok,val); return tok;
    }
    String lookup(String tok){ return map.get(tok); }

    public String mask(String s){ for (var m:maskOrder) s=m.mask(s,this); return s; }
    public String unmask(String s){ for (var m:unmaskOrder) s=m.unmask(s,this); return s; }
}
