package tools.dscode.common.treeparsing;

import com.xpathy.Attribute;
import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.intellij.lang.annotations.Language;
import tools.dscode.common.domoperations.XPathyRegistry;

import static com.xpathy.Attribute.aria_label;
import static com.xpathy.Attribute.name;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.title;
import static com.xpathy.Case.LOWER;
import static tools.dscode.common.domoperations.XPathyMini.orMap;
import static tools.dscode.common.domoperations.XPathyMini.textOp;
import static tools.dscode.common.treeparsing.PhraseExecution.initiateFirstPhraseExecution;
import static tools.dscode.common.treeparsing.RegexUtil.betweenWithEscapes;
import static tools.dscode.common.treeparsing.RegexUtil.normalizeWhitespace;
import static tools.dscode.common.treeparsing.RegexUtil.stripObscureNonText;
import static tools.dscode.common.util.DebugUtils.printDebug;

/**
 * A small palette of nodes used by tests.
 */
public class DictionaryA extends NodeDictionary {
    static {

        XPathyRegistry.registerOrBuilder("*", (category, v, op) -> orMap(
                textOp(op, v),
                () -> XPathy.from(category),
                () -> XPathy.from(Tag.any).byAttribute(role).withCase(LOWER).equals(category.toLowerCase()),
                () -> XPathy.from(Tag.any).byAttribute(title).withCase(LOWER).equals(category.toLowerCase()),
                () -> XPathy.from(Tag.any).byAttribute(id).withCase(LOWER).equals(category.toLowerCase()),
                () -> XPathy.from(Tag.any).byAttribute(name).withCase(LOWER).equals(category.toLowerCase()),
                () -> XPathy.from(Tag.any).byAttribute(aria_label).withCase(LOWER).equals(category.toLowerCase())
        ));


        XPathyRegistry.registerOrBuilder("Button", (category, v, op) -> orMap(
                textOp(op, v),
                () -> XPathy.from(Tag.button),
                () -> XPathy.from(Tag.img).byAttribute(role).equals("button")
        ));

        XPathyRegistry.registerOrBuilder("Link", (category, v, op) -> orMap(
                textOp(op, v),
                () -> XPathy.from(Tag.any).byAttribute(role).equals("link").or().byAttribute(aria_label).equals("link"),
                () -> XPathy.from(Tag.a)
        ));

    }

//    public static void main(String[] args) {
//
//
//
//        ChromiumDriver driver = createChromeDriver();
//        driver.get("https://www.iana.org/help/example-domains");
//        DictionaryA dict = new DictionaryA();
//        String input = """
//                click the "RFC 2606" Link
//                """;
//        LineExecution lineData = dict.getLineExecutionData(input);
//    }
//
//    public LineExecution getLineExecutionData(String input) {
//        MatchNode lineNode = parse(input);
//        LineExecution lineExecution = new LineExecution(lineNode);
//
//        return lineExecution;
//    }


    public static final @Language("RegExp") String punc = ",;\\.\\?!";


    ParseNode line = new ParseNode("^.*$") {
        @Override
        public String onCapture(MatchNode self) {
            return stripObscureNonText(self.originalText().strip());
        }
    };

    ParseNode quoteMask = new ParseNode(betweenWithEscapes("\"", "\"")) {
        @Override
        public String onCapture(String s) {
            return s.substring(1, s.length() - 1);
        }
    };


    ParseNode preProcess = new ParseNode("^.*$") {
        @Override
        public String onCapture(String s) {
            return normalizeWhitespace(s)
                    .replaceAll("(?i)\\b(?:the|then|a)\\b", "")
                    .replaceAll("(\\d+)(?:\\\s*(?:st|nd|rd|th))", "#$1")
//                    .replaceAll("([^" + punc + "]$)", "$1 -")
                    .replaceAll("\\bverifies\\b", "verify")
                    .replaceAll("\\bensures\\b", "ensure")
                    .replaceAll("\\bno\\b|n't\\b", " not");
        }

        public String onSubstitute(MatchNode self) {
            return self.modifiedText();
        }
    };


    //    ParseNode phrase = new ParseNode("(?<conjunction>\\b(?:and|or)\\b)?\\s*(?i:(?<context>from|after|before|for|in|below|above|left of|right of)\\b)?(?<body>[^" + punc + "]+)(?<punc>[" + punc + "])");
    ParseNode phrase = new ParseNode("(?<conjunction>\\b(?:and|or)\\b)?\\s*(?i:(?<context>from|after|before|for|in|below|above|left of|right of)\\b)?(?<body>[^" + punc + "]+)(?<punc>[" + punc + "])?") {
        @Override
        public String onCapture(MatchNode self) {
            String context = self.resolvedGroupText("context");
            printDebug("@@##context: " + context);
            if (!context.isEmpty() && Character.isUpperCase(context.charAt(0)))
                self.putToLocalState("newContext", true);
            self.putToLocalState("context", context.toLowerCase());
            self.putToLocalState("conjunction", self.resolvedGroupText("conjunction"));
            String termination = self.resolvedGroupText("punc");
            if (termination == null || termination.isBlank()) termination = "";
            printDebug("@@##termination: " + termination);
            self.putToLocalState("termination", termination);
            if (self.localStateBoolean("context")) {
                self.localState().put("skip:action", "true");
                self.localState().put("skip:assertion", "true");
                self.localState().put("skip:assertionType", "true");
            }
            //            self.getAncestor("line").putToLocalState("phrase", self);
//            return colorizeBookends(self.originalText(), BOLD(), BRIGHT_GREEN_TEXT());
            return self.originalText();
        }

        @Override
        public String onSubstitute(MatchNode self) {
            PhraseExecution lastPhraseExecution = (PhraseExecution) self.getFromGlobalState("lastPhraseExecution");
            printDebug("@@##lastPhraseExecution: " + lastPhraseExecution);
            if (lastPhraseExecution == null) {
                lastPhraseExecution = initiateFirstPhraseExecution();
            }
            else {
                printDebug("@@##lastPhraseExecution.text: " + lastPhraseExecution.text);
                printDebug("@@##lastPhraseExecution.termination: " + lastPhraseExecution.termination);
                if ((!(lastPhraseExecution.termination.equals(";") || lastPhraseExecution.termination.equals(",")))) {
                    printDebug("@@##setting newContext to true.   " + lastPhraseExecution);
                    self.putToLocalState("newContext", true);
                }
            }


            self.putToLocalState("context", self.resolvedGroupText("context"));
            self.putToGlobalState("lastPhraseExecution", lastPhraseExecution.initiateNextPhraseExecution(self));
            return self.token();
        }
    };

    ParseNode predicate = new ParseNode("(?:\\b(?<predicateType>of|starting with|containing)\\s+(?<predicateVal>\\d+|<<quoteMask>>))") {
        @Override
        public String onCapture(MatchNode self) {
            self.putToLocalState("predicateType", self.resolvedGroupText("predicateType"));
            self.putToLocalState("predicateVal", self.resolvedGroupText("predicateVal"));
            return self.originalText();
        }
    };


    ParseNode elementMatch = new ParseNode("(?:(?<selection>every,any)\\s+)?(?:(?<elementPosition>\\bfirst|\\blast|#\\d+)\\s+)?(?<text><<quoteMask>>)?\\s+(?<type>(?:\\b[A-Z][a-zA-Z]+\\b\\s*)+)(?<elPredicate>(?:with\\s+(?<attrName>[a-z]+)?)?\\s+(?<predicate><<predicate>>))?") {
        @Override
        public String onSubstitute(MatchNode self) {
//            self.getAncestor("phrase").putToLocalState("elementMatch", self);
            self.putToLocalState("selectionType", self.resolvedGroupText("selectionType"));
            String elementPosition = self.resolvedGroupText("elementPosition");
            if (elementPosition == null || elementPosition.isBlank() || elementPosition.equals("first"))
                elementPosition = "1";
            self.putToLocalState("elementPosition", elementPosition.replaceAll("#", ""));
            self.putToLocalState("text", self.resolvedGroupText("text"));
            self.putToLocalState("type", self.resolvedGroupText("type"));
            self.putToLocalState("attrName", self.resolvedGroupText("attrName"));
            self.putToLocalState("predicate", self.resolvedGroupText("predicate"));
            return self.originalText();
        }
    };


    ParseNode valueMatch = new ParseNode("\\s(?<value>\\d+|<<quoteMask>>)(?<unitMatch>\\s+(?<unit>minute|second|number|text)s?\\b)?") {
        @Override
        public String onSubstitute(MatchNode self) {
            String count = self.resolvedGroupText("value");
            String unit = self.resolvedGroupText("unit");
            self.putToLocalState("value", count);
            self.putToLocalState("unit", unit);
            return self.originalText();
        }
    };


    //    ParseNode assertionType = new ParseNode("\\b(?<base>ensure|verify)(?:s)\\b") {
    ParseNode assertionType = new ParseNode("\\b(ensure|verify)\\b") {
        @Override
        public String onSubstitute(MatchNode self) {
            self.parent().putToLocalState("assertionType", self.originalText());
            self.parent().localState().put("skip:action", "true");
            return self.originalText();
        }
    };

    ParseNode action = new ParseNode("\\b(?<base>press|dragAndDrop|double click|right click|hover|move|click|enter|scroll|wait|overwrite)(?:s|ed|ing|es)?\\b") {
        @Override
        public String onCapture(MatchNode self) {
            return self.resolvedGroupText("base").replaceAll("move", "hover");
        }
    };


    ParseNode not = new ParseNode("\\bnot\\b") {
        @Override
        public String onCapture(MatchNode self) {
            return self.originalText();
        }
    };

    //    ParseNode assertion = new ParseNode("\\b(?<base>equal|less(?:er)?|greater|less|is)(?=\\s+(?:<<quoteMask>>|<<valueMatch>>|<<elementMatch>>)(s|ed|ing|es)?)\\b")
    ParseNode assertion = new ParseNode("\\b(?:displayed|equal|less(?:er)?|greater|less)\\b") {
        @Override
        public String onCapture(MatchNode self) {
            self.parent().putToLocalState("assertion", self.originalText());
            return self.originalText();
        }
    };
    // Build the hierarchy AFTER the nodes above exist
    ParseNode root = buildFromYaml("""
            line:
              - quoteMask
              - preProcess
              - phrase:
                - predicate
                - elementMatch
                - valueMatch
                - assertionType
                - assertion
                - action
            """);
}
