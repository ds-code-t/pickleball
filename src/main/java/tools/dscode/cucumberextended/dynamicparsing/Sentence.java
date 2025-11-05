package tools.dscode.cucumberextended.dynamicparsing;

import tools.dscode.cucumberextended.dynamicparsing.BookEndReplacement;
import tools.dscode.cucumberextended.dynamicparsing.Masker;
import tools.dscode.cucumberextended.dynamicparsing.MatchGroup;
import tools.dscode.cucumberextended.dynamicparsing.TextMasking;

// Sentence.java (thin wrapper / example wiring)
public final class Sentence {
    private final TextMasking tm;
    public Sentence(TextMasking tm){ this.tm=tm; }

    public static Sentence standard(){
        var quotes = new Masker("Q",
                new BookEndReplacement("SQ","'","'"),
                new BookEndReplacement("DQ","\"","\"")
        );
        var brackets = new Masker("B",
                new BookEndReplacement("PR","(",")"),
                new BookEndReplacement("BK","[","]"),
                new BookEndReplacement("CB","{","}")
        );
        // example extra: whole @... mention
        var mentions = new Masker("M",
                new MatchGroup("AT","(?<!\\\\)(?:\\\\\\\\)*@[A-Za-z0-9_]+")
        );
        var tm = new TextMasking().orderMask(quotes, brackets, mentions)
                .orderUnmask(mentions, brackets, quotes);
        return new Sentence(tm);
    }

    public String mask(String s){ return tm.mask(s); }
    public String unmask(String s){ return tm.unmask(s); }
}
