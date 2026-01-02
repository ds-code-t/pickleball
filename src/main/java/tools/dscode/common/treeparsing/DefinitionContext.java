package tools.dscode.common.treeparsing;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.intellij.lang.annotations.Language;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyBuilder;

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
import static com.xpathy.Tag.textarea;
import static tools.dscode.common.GlobalConstants.BOOK_END;
import static tools.dscode.common.domoperations.VisibilityConditions.extractPredicate;
import static tools.dscode.common.domoperations.VisibilityConditions.invisible;
import static tools.dscode.common.domoperations.VisibilityConditions.visible;
import static tools.dscode.common.treeparsing.RegexUtil.betweenWithEscapes;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.KEY_NAME;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.PLACE_HOLDER_MATCH;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.VALUE_TYPE_MATCH;

public final class DefinitionContext {

    public static final @Language("RegExp") String NUMERIC_TOKEN =
            "(?<!\\S)-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?!\\S)";


    private DefinitionContext() {
        // utility class
    }


    public static NodeDictionary DEFAULT_NODE_DICTIONARY = new NodeDictionary() {


//        public static final @Language("RegExp") String punc = ",;\\.\\?!";


        ParseNode line = new ParseNode("^.*$") {
            public String onSubstitute(MatchNode self) {
                return " " + self.originalText() + " ";
            }
        };

        ParseNode quoteMask = new ParseNode(betweenWithEscapes(BOOK_END, BOOK_END) + "|" + NUMERIC_TOKEN) {
            @Override
            public String onCapture(String s) {

                return s.substring(1, s.length() - 1);
            }
        };

        ParseNode position = new ParseNode("#\\d+");

        ParseNode numericMask = new ParseNode(NUMERIC_TOKEN);

        ParseNode keyTransform = new ParseNode("\\bas\\s+(?<keyName><<quoteMask>>)(?!\\s*[A-Z])") {
            public String onSubstitute(MatchNode self) {
                String keyName = self.groups().get("keyName");

                return keyName + " " + VALUE_TYPE_MATCH +  KEY_NAME;
            }
        };

        ParseNode valueMask = new ParseNode("<<numericMask>>|<<quoteMask>>") {
            @Override
            public String onCapture(MatchNode self) {
                return self.unmask(self.originalText());
            }
        };


//        ParseNode quoteMask = new ParseNode(betweenWithEscapes("\"", "\"")) {
//            @Override
//            public String onCapture(String s) {
//                return s.substring(1, s.length() - 1);
//            }
//        };


//        ParseNode preProcess = new ParseNode("^.*$") {
//            @Override
//            public String onCapture(String s) {
//                return normalizeWhitespace(s)
//                        .replaceAll("(?i)\\b(?:the|then|a)\\b", "")
//                        .replaceAll("(\\d+)\\s*(?:st|nd|rd|th)", "#$1")
//                        .replaceAll("\\bverifies\\b", "verify")
//                        .replaceAll("\\bensures\\b", "ensure")
//                        .replaceAll("\\bare\\b", "is")
//                        .replaceAll("(?:\\bno|n't)\\b", " not ")
//                        .replaceAll("\\bhave\\b", "has");
//            }
//
//            public String onSubstitute(MatchNode self) {
//                return self.modifiedText();
//            }
//        };


        //
        ParseNode phrase = new ParseNode("^(?<separatorA>\\bthen\\b)?(?<conjunction>\\b(?:and|or)\\b)?(?<separatorB>\\bthen\\b)?\\s*(?<conditional>\\b(?:else\\s+if|else|if)\\b)?\\s*(?i:(?<context>from|after|before|for|in|below|above|left of|right of)\\b)?(?<body>.*)$") {
            @Override
            public String onCapture(MatchNode self) {
                if (self.localStateBoolean("separatorA" , "separatorB")) {
                    self.putToLocalState("separator", "true");
                }
                String context = self.resolvedGroupText("context");

                if (!context.isEmpty() && Character.isUpperCase(context.charAt(0)))
                    self.putToLocalState("newStartContext", true);
                self.putToLocalState("context", context.toLowerCase());
                String conditional = self.resolvedGroupText("conditional");
                self.putToLocalState("conditional", conditional);
                self.putToLocalState("conjunction", self.resolvedGroupText("conjunction"));
                self.putToLocalState("body", self.resolvedGroupText("body"));
//                String termination = self.resolvedGroupText("punc");
//                if (termination == null || termination.isBlank()) termination = "";
//                self.putToLocalState("termination", termination);
                if (conditional.contains("if")) {
                    self.putToLocalState("assertionType", "conditional");
                    self.localState().put("skip:action", "true");
                    self.localState().put("skip:assertionType", "true");
                } else if (self.localStateBoolean("context")) {
                    self.localState().put("skip:action", "true");
                    self.localState().put("skip:assertion", "true");
                    self.localState().put("skip:assertionType", "true");
                    self.localState().put("skip:defaultAssertion", "true");
                }
                //            self.getAncestor("line").putToLocalState("phrase", self);
//            return colorizeBookends(self.originalText(), BOLD(), BRIGHT_GREEN_TEXT());
                return self.originalText();
            }

            @Override
            public String onSubstitute(MatchNode self) {
                return self.token();
            }
        };

        ParseNode predicate = new ParseNode("(?:\\b(?<predicateType>starting with|ending with|containing|equaling|of)\\s+(?<predicateVal><<valueMask>>))") {
            @Override
            public String onCapture(MatchNode self) {

                String predicateType = self.resolvedGroupText("predicateType");
                predicateType = predicateType.replaceAll("with", "").replaceAll("of", "equaling").trim();
                self.putToLocalState("predicateType", predicateType);
                self.putToLocalState("predicateVal", self.resolvedGroupText("predicateVal"));
                return self.originalText();
            }
        };


        ParseNode elementMatch = new ParseNode("(?:(?<selectionType>every|any|none\\s+of|none|no)\\b\\s*)?(?:(?<elementPosition>\\bfirst|\\blast|<<position>>)\\s+)?(?:(?<state>(?:un)?(?:checked|selected|enabled|disabled|expanded|collapsed))\\s+)?(?<text><<valueMask>>)?\\s+(?<type>(?:\\b[A-Z][a-zA-Z]+\\b\\s*)+)(?<elPredicate>(?<predicate>\\s*<<predicate>>))*\\s*(?<atrPredicate>\\bwith\\s+[a-z]+\\s+<<predicate>>\\s*)*") {
            @Override
            public String onSubstitute(MatchNode self) {
                self.putToLocalState("fullText", self.unmask(self.groups().get(0)));

//            self.getAncestor("phrase").putToLocalState("elementMatch", self);
                self.putToLocalState("selectionType", self.resolvedGroupText("selectionType")
                        .replaceAll("of", "").trim()
                        .replaceAll("no", "none"));
                String elementPosition = self.resolvedGroupText("elementPosition");
                if (elementPosition.isBlank() || elementPosition.equals("first"))
                    elementPosition = "1";
                self.putToLocalState("elementPosition", elementPosition.replaceAll("#", ""));
                self.putToLocalState("state", self.resolvedGroupText("state"));
                if (self.groups().containsKey("text")) {
                    self.putToLocalState("text", self.resolvedGroupText("text"));
                }
                self.putToLocalState("type", self.resolvedGroupText("type"));


                self.putToLocalState("elPredicate", self.groups().get("elPredicate"));
                self.putToLocalState("atrPredicate", self.groups().get("atrPredicate"));
                return self.token();
            }
        };

        ParseNode valueTypes = new ParseNode("(?<valueTypes>\\s(?:(?:and\\s+)?[a-z-]+\\s+of\\s+)+)(?<element><<elementMatch>>)") {
            @Override
            public String onSubstitute(MatchNode self) {
                String valueTypes = self.resolvedGroupText("valueTypes").replaceAll("\\b(?:and|of)\\b", "").trim();
                String elementToken = self.groups().get("element");
                MatchNode elementMatchNode = self.getMatchNode(elementToken);

                elementMatchNode.putToLocalState("valueTypes", valueTypes);

//                return elOrValToken;
                return " " + self.groups().get("element") + " ";
            }

        };


        ParseNode valueTransform = new ParseNode("\\s(?<value><<valueMask>>)(?!\\s*[A-Z])(?<unitMatch>\\s+(?<unit>second|minute|hour|day|week|month|year|time|number|integer|decimal|color|text)s?\\b)?") {
            @Override
            public String onSubstitute(MatchNode self) {

                String value = " " + self.groups().get("value") + " ";
                String unit = " " + VALUE_TYPE_MATCH + self.groups().getOrDefault("unit", "").trim().replaceAll("\\s$", "") + " ";

                return value + unit;
            }
        };

//        ParseNode valueMatch = new ParseNode("\\s(?<value><<valueMask>>)(?<unitMatch>\\s+(?<unit>minute|second|number|text)s?\\b)?") {
//            @Override
//            public String onSubstitute(MatchNode self) {
//                String count = self.resolvedGroupText("value");
//                String unit = self.resolvedGroupText("unit");
//                self.putToLocalState("value", count);
//                self.putToLocalState("unit", unit);
//                return self.token();
//            }
//        };

//        ParseNode valueTypes = new ParseNode("\\s(?<valueTypes>(?:[a-z-]+\\s+of\\s+)+)?(?<val><<elementMatch>>|<<valueMatch>>)") {
//            @Override
//            public String onSubstitute(MatchNode self) {
//                String elOrValToken = self.groups().get("val");
//                MatchNode elOrValNode = self.getMatchNode(elOrValToken);
//
//                elOrValNode.putToLocalState("valueTypes", self.resolvedGroupText("valueTypes"));
//
        ////                return elOrValToken;
//                return self.originalText();
//            }
//        };

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
                self.parent().putToLocalState("operationIndex", self.start);
                return self.resolvedGroupText("base").replaceAll("move", "hover");
            }
        };

//        ParseNode key = new ParseNode("\\bas\\s+(?<keyName><<valueMask>>)") {
//            @Override
//            public String onCapture(MatchNode self) {
//                self.parent().putToLocalState("keyName", self.resolvedGroupText("keyName"));
////                return self.originalText();
//                return " ";
//            }
//        };


        ParseNode no = new ParseNode("\\bno\\b") {
            @Override
            public String onCapture(MatchNode self) {
                self.parent().putToLocalState(self.originalText(), self.originalText());
//                return self.originalText();
                return " ";
            }
        };

        ParseNode assertion = new ParseNode("\\b(?:starts?\\s+with|ends?\\s+with|contains?|match(?:es)?|displayed|(?:un)?selected|(?:un)?checked|enabled|disabled|equals?|less\\s+than|greater\\s+than|has\\s+values?|(?:has|is)\\s+blank)\\b") {
            @Override
            public String onCapture(MatchNode self) {
                String assertion = self.originalText().trim()
                        .replaceAll("(start|end|contain|match|equal|values)(?:es|s)", "$1")
                        .replaceAll("^(?:is|has)", "")
                        .replaceAll("\\s+", " ")
                        .trim();
                self.parent().putToLocalState("assertion", assertion);
                self.parent().putToLocalState("operationIndex", self.start);
                return self.originalText();
            }
        };


        ParseNode defaultAssertion = new ParseNode("<<elementMatch>>[^_]*\\s(?<defaultAssertion>is)\\s[^_]*<<elementMatch>>") {
            @Override
            public String onSubstitute(MatchNode self) {
                MatchNode parentNode = self.parent();
                if(!parentNode.localStateBoolean("context", "action", "assertion")) {
                    parentNode.putToLocalState("assertion", "equal");
                    parentNode.putToLocalState("operationIndex",self.groups().start("defaultAssertion"));
                }
                return self.originalText();
            }
        };


        ParseNode itPlaceholder = new ParseNode("\\bit\\b") {
            @Override
            public String onSubstitute(MatchNode self) {
                MatchNode parentNode = self.parent();
                return " " +  PLACE_HOLDER_MATCH + " ";
//                if(parentNode.localStateBoolean( "action", "assertion")) {
//                    return " " +  PLACE_HOLDER_MATCH + " ";
//                }
//                return self.token();
            }
        };

        ParseNode root = buildFromYaml("""
                line:
                  - quoteMask
                  - position
                  - numericMask
                  - keyTransform
                  - valueMask
                  - phrase:
                    - predicate
                    - itPlaceholder
                    - valueTransform
                    - elementMatch
                    - no
                    - valueTypes
                    - assertionType
                    - action
                    - assertion
                    - defaultAssertion
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
            category("Link").inheritsFrom("Text")
                    .or(
                            (category, v, op) ->
                                    XPathy.from(any).byAttribute(role).equals("link")
                                            .or().byAttribute(aria_label).equals("link"),
                            (category, v, op) ->
                                    XPathy.from(Tag.a)
                    );


            category("Dropdown").inheritsFrom("forLabel").and((category, v, op) ->
                    XPathy.from(select)).or(
                    (category, v, op) -> XPathyBuilder.build(select, id, v, op),
                    (category, v, op) -> XPathyBuilder.build(select, title, v, op),
                    (category, v, op) -> XPathyBuilder.build(select, name, v, op)
            );


            //
            // Textbox  (two registration blocks preserved exactly)
            //
            category("Textbox").inheritsFrom("forLabel")
                    .and((category, v, op) ->
                            input.byAttribute(type).equals("text").or().byAttribute(type).equals("password").or().byAttribute(type).equals("email"))
                    .or(
                            (category, v, op) ->
                                    XPathyBuilder.build(input, placeholder, v, op)
                    );

            category("Textarea").inheritsFrom("forLabel")
                    .and((category, v, op) ->
                            XPathy.from(textarea)
                    )
                    .or(
                            (category, v, op) ->
                                    XPathyBuilder.build(textarea, placeholder, v, op)
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
                        if (v == null || v.isNullOrBlank()) {
                            return null; // no label text to match, skip this builder
                        }
                        return new XPathy("//*[@id][@id = //*[normalize-space(text())='" + v + "']/@for]");
                    });


            //
            // "*" fallback OR builders
            //

            category("*")
                    .or(
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
