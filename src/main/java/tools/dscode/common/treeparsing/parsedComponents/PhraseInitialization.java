//package tools.dscode.common.treeparsing.parsedComponents;
//
//import tools.dscode.common.treeparsing.preparsing.LineData;
//import tools.dscode.common.treeparsing.preparsing.ParsedLine;
//
//public class PhraseInitialization {
//    public final int position;
//    public final String text;
//    public final char delimiter;
//    public final LineData parsedLine;
//    public final PhraseInitialization previousInitialization;
//    public PhraseInitialization nextInitialization;
//    public PhraseData phraseData;
//
//    public PhraseInitialization(String text, char delimiter, LineData parsedLine) {
//        this.text = text;
//        this.delimiter = delimiter;
//        this.parsedLine = parsedLine;
//        this.position = parsedLine.initializationData.size();
//        this.previousInitialization = parsedLine.initializationData.isEmpty() ? null : parsedLine.initializationData.getLast();
//    }
//
//    @Override
//    public String toString() {
//        return text + delimiter;
//    }
//}
