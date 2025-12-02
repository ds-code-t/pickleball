package tools.dscode.common.treeparsing.preparsing;

import java.util.Collection;
import java.util.List;

public final class ParsedLine extends LineData {

    public ParsedLine(String input) {
        this(input, List.of(',', ';', ':', '.', '!', '?'));
    }

    public ParsedLine(String input, Collection<Character> delimiters) {
        super(input, delimiters);
    }




}
