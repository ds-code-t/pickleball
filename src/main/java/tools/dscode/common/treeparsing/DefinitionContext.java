package tools.dscode.common.treeparsing;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import io.cucumber.core.runner.StepExtension;
import org.intellij.lang.annotations.Language;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import static com.xpathy.Attribute.aria_label;
import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.name;
import static com.xpathy.Attribute.placeholder;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.title;
import static com.xpathy.Attribute.type;
import static com.xpathy.Case.LOWER;
import static com.xpathy.Tag.any;
import static com.xpathy.Tag.input;
import static com.xpathy.Tag.select;
import static tools.dscode.common.domoperations.VisibilityConditions.extractPredicate;
import static tools.dscode.common.domoperations.VisibilityConditions.invisible;
import static tools.dscode.common.domoperations.VisibilityConditions.visible;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedText;
import static tools.dscode.common.treeparsing.RegexUtil.betweenWithEscapes;
import static tools.dscode.common.treeparsing.RegexUtil.normalizeWhitespace;
import static tools.dscode.common.util.DebugUtils.printDebug;

public final class DefinitionContext {

    private DefinitionContext() {
        // utility class
    }


    public static NodeDictionary DEFAULT_NODE_DICTIONARY = new NodeDictionary() {


        public static final @Language("RegExp") String punc = ",;\\.\\?!";


        ParseNode line = new ParseNode("^.*$");
//        ParseNode line = new ParseNode("^.*$") {
//            @Override
//            public String onCapture(MatchNode self) {
//                return stripObscureNonText(self.originalText().strip());
//            }
//        };

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
                        .replaceAll("\\bverifies\\b", "verify")
                        .replaceAll("\\bensures\\b", "ensure")
                        .replaceAll("\\bno\\b|n't\\b", " not");
            }

            public String onSubstitute(MatchNode self) {
                return self.modifiedText();
            }
        };


        //
        //    ParseNode phrase = new ParseNode("(?<conjunction>\\b(?:and|or)\\b)?\\s*(?i:(?<context>from|after|before|for|in|below|above|left of|right of)\\b)?(?<body>[^" + punc + "]+)(?<punc>[" + punc + "])");
//        ParseNode phrase = new ParseNode("(?<conjunction>\\b(?:and|or)\\b)?\\s*(?i:(?<context>from|after|before|for|in|below|above|left of|right of)\\b)?(?<body>[^" + punc + "]+)(?<punc>[" + punc + "])?") {
        ParseNode phrase = new ParseNode("^(?<conjunction>\\b(?:and|or)\\b)?\\s*(?i:(?<context>from|after|before|for|in|below|above|left of|right of)\\b)?(?<body>.*)$") {
            @Override
            public String onCapture(MatchNode self) {

                String context = self.resolvedGroupText("context");

                if (!context.isEmpty() && Character.isUpperCase(context.charAt(0)))
                    self.putToLocalState("newStartContext", true);
                self.putToLocalState("context", context.toLowerCase());
                self.putToLocalState("conjunction", self.resolvedGroupText("conjunction"));
//                String termination = self.resolvedGroupText("punc");
//                if (termination == null || termination.isBlank()) termination = "";
//                self.putToLocalState("termination", termination);
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
//                StepExtension currentStep = getCurrentStep();
////                PhraseData lastPhraseExecution = (PhraseExecution) self.getFromGlobalState("lastPhraseExecution");
//                PhraseData lastPhraseExecution = currentStep.contextPhraseExecution;
//                printDebug("@@##lastPhraseExecution: " + lastPhraseExecution);
//                if (lastPhraseExecution == null) {
//                    lastPhraseExecution = currentStep.getParentContextPhraseExecution() == null ? initiateFirstPhraseExecution() : currentStep.getParentContextPhraseExecution();
//                } else {
//                    if ((!(lastPhraseExecution.termination.equals(";") || lastPhraseExecution.termination.equals(",")))) {
//                        self.putToLocalState("newContext", true);
//                    }
//                }
//
//
//                self.putToLocalState("context", self.resolvedGroupText("context"));
////                self.putToGlobalState("lastPhraseExecution", lastPhraseExecution.initiateNextPhraseExecution(self));
//                currentStep.contextPhraseExecution = lastPhraseExecution.initiateNextPhraseExecution(self);
                return self.token();
            }
        };

        ParseNode predicate = new ParseNode("(?:\\b(?<predicateType>starting with|containing)\\s+(?<predicateVal>\\d+|<<quoteMask>>))") {
            @Override
            public String onCapture(MatchNode self) {

                self.putToLocalState("predicateType", self.resolvedGroupText("predicateType"));
                self.putToLocalState("predicateVal", self.resolvedGroupText("predicateVal"));
                return self.originalText();
            }
        };


//        ParseNode elementMatch = new ParseNode("(?:(?<selection>every,any)\\s+)?(?:(?<elementPosition>\\bfirst|\\blast|#\\d+)\\s+)?(?:(?<state>(?:un)?(?:checked|selected|enabled|disabled|expanded|collapsed))\\s+)?(?<text><<quoteMask>>)?\\s+(?<type>(?:\\b[A-Z][a-zA-Z]+\\b\\s*)+)(?<elPredicate>(?:with\\s+(?<attrName>[a-z]+)?)?\\s+(?<predicate><<predicate>>))?");
        ParseNode elementMatch = new ParseNode("(?:(?<selection>every,any)\\s+)?(?:(?<elementPosition>\\bfirst|\\blast|#\\d+)\\s+)?(?:(?<state>(?:un)?(?:checked|selected|enabled|disabled|expanded|collapsed))\\s+)?(?<text><<quoteMask>>)?\\s+(?<type>(?:\\b[A-Z][a-zA-Z]+\\b\\s*)+)(?<elPredicate>(?:with\\s+(?<attrName>[a-z]+)?)?\\s*(?<predicate><<predicate>>))?") {
            @Override
            public String onSubstitute(MatchNode self) {

//            self.getAncestor("phrase").putToLocalState("elementMatch", self);
                self.putToLocalState("selectionType", self.resolvedGroupText("selectionType"));
                String elementPosition = self.resolvedGroupText("elementPosition");
                if (elementPosition.isBlank() || elementPosition.equals("first"))
                    elementPosition = "1";
                self.putToLocalState("elementPosition", elementPosition.replaceAll("#", ""));
                self.putToLocalState("state", self.resolvedGroupText("state"));
                self.putToLocalState("text", self.resolvedGroupText("text"));
                self.putToLocalState("type", self.resolvedGroupText("type"));
                self.putToLocalState("attrName", self.resolvedGroupText("attrName"));
                self.putToLocalState("elPredicate", self.groups().get("elPredicate"));
                return self.token();
            }
        };

        ParseNode valueMatch = new ParseNode("\\s(?<value>\\d+|<<quoteMask>>)(?<unitMatch>\\s+(?<unit>minute|second|number|text)s?\\b)?") {
            @Override
            public String onSubstitute(MatchNode self) {

                String count = self.resolvedGroupText("value");
                String unit = self.resolvedGroupText("unit");
                self.putToLocalState("value", count);
                self.putToLocalState("unit", unit);
                return self.token();
            }
        };

        ParseNode valueTypes = new ParseNode("\\s(?<valueTypes>(?:[a-z-]+\\s+of\\s+)+)?(?<val><<elementMatch>>|<<valueMatch>>)") {
            @Override
            public String onSubstitute(MatchNode self) {
                String elOrValToken = self.groups().get("val");
                MatchNode elOrValNode = self.getMatchNode(elOrValToken);

                elOrValNode.putToLocalState("valueTypes", self.resolvedGroupText("valueTypes"));

//                return elOrValToken;
                return self.originalText();
            }
        };

        ParseNode assertionType = new ParseNode("\\b(ensure|verify)\\b") {
            @Override
            public String onSubstitute(MatchNode self) {

                self.parent().putToLocalState("assertionType", self.originalText());
                self.parent().localState().put("skip:action", "true");
                return self.originalText();
            }
        };

        ParseNode action = new ParseNode("\\b(?<base>select|press|dragAndDrop|double click|right click|hover|move|click|enter|scroll|wait|overwrite|save)(?:s|ed|ing|es)?\\b") {
            @Override
            public String onCapture(MatchNode self) {

                self.parent().putToLocalState("action", self.resolvedGroupText("base"));
                return self.resolvedGroupText("base").replaceAll("move", "hover");
            }
        };

        ParseNode key = new ParseNode("\\bas\\s+(?<keyName><<quoteMask>>)") {
            @Override
            public String onCapture(MatchNode self) {
                self.parent().putToLocalState("keyName", self.resolvedGroupText("keyName"));
//                return self.originalText();
                return " ";
            }
        };


        ParseNode not = new ParseNode("\\bnot\\b") {
            @Override
            public String onCapture(MatchNode self) {
                self.parent().putToLocalState("not", "not");
//                return self.originalText();
                return " ";
            }
        };

        //    ParseNode assertion = new ParseNode("\\b(?<base>equal|less(?:er)?|greater|less|is)(?=\\s+(?:<<quoteMask>>|<<valueMatch>>|<<elementMatch>>)(s|ed|ing|es)?)\\b")
        ParseNode assertion = new ParseNode("\\b(?:starts? with|ends? with|contains?|match(?:es)?|displayed|equals?|less(?:er)?|greater|less)\\b") {
            @Override
            public String onCapture(MatchNode self) {

                self.parent().putToLocalState("assertion", self.originalText());
                return self.originalText();
            }
        };

        ParseNode elementState = new ParseNode("\\b(?<false>un)?\\s+(?<state>checked|selected|enabled|disabled|expanded|collapsed)<<elementMatch>>") {
            @Override
            public String onCapture(MatchNode self) {
                self.parent().putToLocalState("not", "not");
//                return self.originalText();
                return " ";
            }
        };

        ParseNode root = buildFromYaml("""
                line:
                  - quoteMask
                  - phrase:
                    - not
                    - key
                    - predicate
                    - elementMatch
                    - valueMatch
                    - assertionType
                    - assertion
                    - action
                    - valueTypes
                """);

        // Build the hierarchy AFTER the nodes above exist
//        ParseNode root = buildFromYaml("""
//                line:
//                  - quoteMask
//                  - preProcess
//                  - phrase:
//                    - predicate
//                    - elementMatch
//                    - valueMatch
//                    - assertionType
//                    - assertion
//                    - action
//                """);
    };

    public static ExecutionDictionary DEFAULT_EXECUTION_DICTIONARY = new ExecutionDictionary() {
        @Override
        protected void register() {

//            registerDefaultStartingContext((category, v, op, ctx) -> {

//                return wrapContext(ctx.switchTo().defaultContent());
//            });

            //
            // Frame
            //
            category("Frame")
                    .and(
                            (category, v, op) ->
                                    XPathy.from(Tag.iframe).byAttribute(title).haveIt()
                    );
            category("IFrame").inheritsFrom("Frame");


            //
            // IframeResult
            //
//            category("IframeResult")
//                    .and(
//                            (category, v, op) ->
//                                    XPathy.from(Tag.iframe).byAttribute(id).equals("iframeResult")
//                    );


            //
            // Button
            //
            category("Button").inheritsFrom("forLabel", CONTAINS_TEXT)
                    .or(
                            (category, v, op) -> XPathy.from(Tag.button),
                            (category, v, op) -> XPathy.from(Tag.img).byAttribute(role).equals("button"),
                            (category, v, op) -> XPathy.from(Tag.a).byAttribute(role).equals("button")
                    );


            category("Submit Button").or(
                    (category, v, op) -> input.byAttribute(type).equals("submit")
            );

            //
            // Link
            //
            category("Link")
                    .or(
                            (category, v, op) ->
                                    XPathy.from(any).byAttribute(role).equals("link")
                                            .or().byAttribute(aria_label).equals("link"),
                            (category, v, op) ->
                                    XPathy.from(Tag.a)
                    ).and(
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


            category(BASE_CATEGORY).and(
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
    };


    private static final ThreadLocal<ExecutionDictionary> EXECUTION_DICTIONARY_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> DEFAULT_EXECUTION_DICTIONARY);


    public static ExecutionDictionary getExecutionDictionary() {
        return EXECUTION_DICTIONARY_THREAD_LOCAL.get();
    }


    public static void setExecutionDictionaryDictionary(ExecutionDictionary dictionary) {
        EXECUTION_DICTIONARY_THREAD_LOCAL.set(dictionary);
    }


    public static void resetExecutionDictionary() {
        EXECUTION_DICTIONARY_THREAD_LOCAL.set(DEFAULT_EXECUTION_DICTIONARY);
    }


    private static final ThreadLocal<NodeDictionary> NODE_DICTIONARY_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> DEFAULT_NODE_DICTIONARY);


    public static NodeDictionary getNodeDictionary() {
        return NODE_DICTIONARY_THREAD_LOCAL.get();
    }


    public static void setNodeDictionary(NodeDictionary dictionary) {
        NODE_DICTIONARY_THREAD_LOCAL.set(dictionary);
    }


    public static void resetNodeDictionary() {
        NODE_DICTIONARY_THREAD_LOCAL.set(DEFAULT_NODE_DICTIONARY);
    }

}
