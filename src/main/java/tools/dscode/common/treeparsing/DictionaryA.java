package tools.dscode.common.treeparsing;

import com.xpathy.Attribute;
import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.intellij.lang.annotations.Language;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.domoperations.XPathyRegistry;

import static com.xpathy.Attribute.aria_label;
import static com.xpathy.Attribute.for_;
import static com.xpathy.Attribute.name;
import static com.xpathy.Attribute.placeholder;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.title;
import static com.xpathy.Attribute.type;
import static com.xpathy.Case.LOWER;
import static com.xpathy.Condition.attribute;
import static com.xpathy.Tag.any;
import static com.xpathy.Tag.input;
import static com.xpathy.Tag.label;
import static com.xpathy.Tag.select;
import static tools.dscode.common.domoperations.VisibilityConditions.extractPredicate;
import static tools.dscode.common.domoperations.VisibilityConditions.invisible;
import static tools.dscode.common.domoperations.VisibilityConditions.visible;
import static tools.dscode.common.domoperations.XPathyMini.applyTextOp;
import static tools.dscode.common.domoperations.XPathyMini.orMap;
import static tools.dscode.common.domoperations.XPathyMini.textOp;
import static tools.dscode.common.domoperations.XPathyRegistry.combineOr;
import static tools.dscode.common.domoperations.XPathyRegistryFluent.category;
import static tools.dscode.common.domoperations.XPathyUtils.deepNormalizedText;
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


        //
        // Frame
        //
        category("Frame")
                .and(
                        (category, v, op) ->
                                XPathy.from(Tag.iframe).byAttribute(title).haveIt()
                );

        //
        // IframeResult
        //
        category("IframeResult")
                .and(
                        (category, v, op) ->
                                XPathy.from(Tag.iframe).byAttribute(id).equals("iframeResult")
                );


        //
        // Button
        //
        category("Button").inheritsFrom("forLabel")
//                .and((category, v, op) -> XPathy.from("descendant-or-self::*")
//                        .byHaving(deepNormalizedText(v)))
                .or(
                        (category, v, op) -> XPathy.from(Tag.button),
                        (category, v, op) -> XPathy.from(Tag.img).byAttribute(role).equals("button"),
                        (category, v, op) -> XPathy.from(Tag.a).byAttribute(role).equals("button")
                );

        //
        // Link
        //
        category("Link")
                .and(
                        (category, v, op) ->
                                XPathy.from(any).byAttribute(role).equals("link")
                                        .or().byAttribute(aria_label).equals("link")
                                        .or().tag(Tag.a),
                        (category, v, op) -> {
                            if (v == null || v.isBlank())
                                return null;
                            return any.byHaving(
                                    XPathy.from("descendant-or-self::*")
                                            .byHaving(deepNormalizedText(v))
                            );
                        }
                );


        category("Dropdown").inheritsFrom("forLabel").and((category, v, op) ->
                XPathy.from(select)).or(
                (category, v, op) -> select.byAttribute(id).equals(v),
                (category, v, op) -> select.byAttribute(title).equals(v),
                (category, v, op) -> select.byAttribute(name).equals(v)
        );


        //
        // Textbox  (two registration blocks preserved exactly)
        //
        category("Textbox").inheritsFrom("forLabel")
                .and((category, v, op) ->
                        input.byAttribute(type).equals("text").or().byAttribute(type).equals("password").or().byAttribute(type).equals("email"))
                .or(
                        (category, v, op) ->
                                input.byAttribute(placeholder).equals(v)
                );


//        category("baseCategory").and(
//                (category, v, op) -> {
//                    XPathy selfInvisible = any.byCondition(invisible());
//                    String invisiblePredicate = extractPredicate("//*", selfInvisible.getXpath());
//
//                    XPathy selfVisible = any.byCondition(visible());
//                    String visiblePredicate = extractPredicate("//*", selfVisible.getXpath());
//
//                    return XPathy.from(
//                            "//*[" +
//                                    visiblePredicate +
//                                    " and not(ancestor::*[" + invisiblePredicate + "])]"
//                    );
//                }
//        );

        category("visible")
                .and(
                        (category, v, op) -> {
                            XPathy selfInvisible = any.byCondition(invisible());
                            String invisiblePredicate = extractPredicate("//*", selfInvisible.getXpath());

                            XPathy selfVisible = any.byCondition(visible());
                            String visiblePredicate = extractPredicate("//*", selfVisible.getXpath());

                            return XPathy.from(
                                    "//*[" +
                                            visiblePredicate +
                                            " and not(ancestor::*[" + invisiblePredicate + "])]"
                            );
                        }
                );

        category("containsText")
                .and(
                        (category, v, op) -> {
                            if (v == null || v.isBlank())
                                return null;
                            return any.byHaving(
                                    XPathy.from("descendant-or-self::*")
                                            .byHaving(deepNormalizedText(v))
                            );
                        }
                );


        category("visibleText")
                .and(
                        (category, v, op) -> {
                            if (v == null || v.isBlank())
                                return null;

                            return combineOr(
                                    any.byHaving(
                                            XPathy.from("descendant-or-self::*")
                                                    .byHaving(deepNormalizedText(v))
                                    ),
                                    any.byHaving(
                                            XPathy.from("preceding::*")
                                                    .byHaving(deepNormalizedText(v))
                                    )
                            );
                        }
                );


        category("forLabel")
                .or((category, v, op) -> {
                    if (v == null || v.isBlank()) {
                        return null; // no label text to match, skip this builder
                    }
                    return new XPathy("//*[@id][@id = //*[normalize-space(text())='" + v + "']/@for]");
                });


        //
        // "*" fallback OR builders
        //
        category("*")
                .or(
//                        (category, v, op) -> XPathy.from(category),
                        (category, v, op) ->
                                XPathy.from(any).byAttribute(role).withCase(LOWER).withNormalizeSpace().equals(category.toLowerCase()),

                        (category, v, op) ->
                                XPathy.from(any).byAttribute(title).withCase(LOWER).withNormalizeSpace().equals(category.toLowerCase()),

                        (category, v, op) ->
                                XPathy.from(any).byAttribute(id).withCase(LOWER).withNormalizeSpace().equals(category.toLowerCase()),

                        (category, v, op) ->
                                XPathy.from(any).byAttribute(name).withCase(LOWER).withNormalizeSpace().equals(category.toLowerCase()),

                        (category, v, op) ->
                                XPathy.from(any).byAttribute(aria_label).withCase(LOWER).withNormalizeSpace().equals(category.toLowerCase())
                );


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
            if (!context.isEmpty() && Character.isUpperCase(context.charAt(0)))
                self.putToLocalState("newContext", true);
            self.putToLocalState("context", context.toLowerCase());
            self.putToLocalState("conjunction", self.resolvedGroupText("conjunction"));
            String termination = self.resolvedGroupText("punc");
            if (termination == null || termination.isBlank()) termination = "";
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
            } else {
                if ((!(lastPhraseExecution.termination.equals(";") || lastPhraseExecution.termination.equals(",")))) {
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

    ParseNode action = new ParseNode("\\b(?<base>select|press|dragAndDrop|double click|right click|hover|move|click|enter|scroll|wait|overwrite)(?:s|ed|ing|es)?\\b") {
        @Override
        public String onCapture(MatchNode self) {
            self.parent().putToLocalState("action", self.resolvedGroupText("base"));
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
