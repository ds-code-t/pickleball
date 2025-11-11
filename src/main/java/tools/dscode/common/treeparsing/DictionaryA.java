package tools.dscode.common.treeparsing;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.intellij.lang.annotations.Language;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.domoperations.XPathyRegistry;

import static com.xpathy.Attribute.*;
import static tools.dscode.common.domoperations.DriverFactory.createChromeDriver;
import static tools.dscode.common.domoperations.XPathyMini.orMap;
import static tools.dscode.common.domoperations.XPathyMini.textOp;
import static tools.dscode.common.treeparsing.RegexUtil.betweenWithEscapes;
import static tools.dscode.common.treeparsing.RegexUtil.normalizeWhitespace;
import static tools.dscode.common.treeparsing.RegexUtil.stripObscureNonText;

/**
 * A small palette of nodes used by tests.
 */
public class DictionaryA extends NodeDictionary {

    public static void main(String[] args) {

        XPathyRegistry.add("Button", (v, op) ->
                orMap(
                        textOp(op, v),
                        () -> XPathy.from(Tag.button),                                       // //button[…]
                        () -> XPathy.from(Tag.img).byAttribute(role).equals("button") // //img[@role='button'][…]
                )
        );

        XPathyRegistry.add("Link", (v, op) ->
                orMap(
                        textOp(op, v),
                        () -> XPathy.from(Tag.img).byAttribute(role).equals("link"),                            // //button[…]
                        () -> XPathy.from(Tag.a)                                       // //button[…]
                )
        );

        ChromiumDriver driver = createChromeDriver();
        driver.get("https://www.iana.org/help/example-domains");
        System.out.println("@@main");
        DictionaryA dict = new DictionaryA();
        String input = """
                click the "RFC 2606" Link
                """;
        LineExecution lineData = dict.getLineExecutionData(input);
        System.out.println("@@lineExecutionData: " + lineData);
        System.out.println("@@lineExecutionData: " + lineData.execute(driver));
    }

    public LineExecution getLineExecutionData(String input) {
        System.out.println("@@getLineExecutionData: " + input);
        MatchNode lineNode = parse(input);
//        MatchNode lineNode = matchNode.getDescendants("line").getFirst();
        LineExecution lineExecution = new LineExecution(lineNode);
        System.out.println("@@lineExecutionData: " + lineExecution);
        System.out.println("@@ineExecutionData.phrases.getFirst().type: " + lineExecution.phrases.getFirst().type);
//        System.out.println("@@ineExecutionData.element " + lineExecution.phrases.getFirst().element);
//        System.out.println("@@ineExecutionData.xPathy " + lineExecution.phrases.getFirst().element.xPathy);

        return lineExecution;
    }


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
            System.out.println("@@preProcess onCapture: " + s);
            return normalizeWhitespace(s)
                    .replaceAll("(?i)\\b(?:the|then|a)\\b", "")
                    .replaceAll("([^" + punc + "]$)", "$1.")
                    .replaceAll("\\bverifies\\b", "verify")
                    .replaceAll("\\bensures\\b", "ensure")
                    .replaceAll("\\bno\\b|n't\\b", " not");
        }

        public String onSubstitute(MatchNode self) {
            return self.modifiedText();
        }
    };


    ParseNode phrase = new ParseNode("(?<conjunction>\\b(?:and|or)\\b)?\\s*(?i:(?<context>from|after|before|for|in)?)(?<body>[^" + punc + "]+)(?<punc>[" + punc + "])") {
        @Override
        public String onCapture(MatchNode self) {
            System.out.println("@@phrase onCapture11: " + self.token() + " " + self.resolvedGroupText("body"));
            self.putToLocalState("context", self.resolvedGroupText("context"));
            self.putToLocalState("conjunctions", self.resolvedGroupText("conjunctions"));
            self.putToLocalState("punc", self.resolvedGroupText("punc"));
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
            System.out.println("@@phrase onSubstitute: " + self.token() + " " + self.resolvedGroupText("end"));
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


    ParseNode element = new ParseNode("(?<text><<quoteMask>>)?\\s+(?<type>(?:\\b[A-Z][a-z]+\\b\\s*)+)(?<elPredicate>(?:with\\s+(?<attrName>[a-z]+)?)?\\s+(?<predicate><<predicate>>))?") {
        @Override
        public String onSubstitute(MatchNode self) {
//            self.getAncestor("phrase").putToLocalState("element", self);
            self.putToLocalState("text", self.resolvedGroupText("text"));
            self.putToLocalState("type", self.resolvedGroupText("type"));
            self.putToLocalState("attrName", self.resolvedGroupText("attrName"));
            self.putToLocalState("predicate", self.resolvedGroupText("predicate"));
            return self.originalText();
        }
    };


    ParseNode value = new ParseNode("\\s(?<count>\\d+|<<quoteMask>>)(?<unitMatch>\\s+(?<unit>minute|second|number|text)s?\\b)?") {
        @Override
        public String onSubstitute(MatchNode self) {
//            self.getAncestor("phrase").putToLocalState("value", self);
            String count = self.resolvedGroupText("count");
            String unit = self.resolvedGroupText("unit");
            self.putToLocalState("count", count);
            self.putToLocalState("unit", unit);
            return self.originalText();
        }
    };


    //    ParseNode assertionType = new ParseNode("\\b(?<base>ensure|verify)(?:s)\\b") {
    ParseNode assertionType = new ParseNode("\\b(ensure|verify)\\b") {
        @Override
        public String onSubstitute(MatchNode self) {
            self.localState().put("skip:action", "true");
            return self.originalText();
        }
    };

    ParseNode action = new ParseNode("\\b(?<base>click|enter|scroll|wait|overwrite)(?:s|ed|ing|es)?\\b") {
        @Override
        public String onCapture(MatchNode self) {
            return self.resolvedGroupText("base");
        }
    };

    ParseNode assertion = new ParseNode("\\b(?<base>equal|less(?:er)?|greater|less|is(?=\\s+(?:<<quoteMask>>|<<value>>|<<element>>)))(s|ed|ing|es)?\\b") {
        @Override
        public String onCapture(MatchNode self) {
            return self.resolvedGroupText("base");
        }
    };

    // Build the hierarchy AFTER the nodes above exist
    ParseNode root = buildFromYaml("""
            line:
              - quoteMask
              - preProcess
              - phrase:
                - predicate
                - element
                - value
                - assertionType
                - assertion
                - action
            """);
}
